package org.uvo.uvostore.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.SyncDirection;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.pos.enums.WebhookStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.SyncWebHookLogRepository;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.pos.PosCatalogSnapshot;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosReconciliationService;
import org.uvo.uvostore.service.pos.PosReconciliationSummary;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS-4. La conciliación corrige hacia el POS lo que divergió, sin deshacer ventas web en vuelo.
 *
 * <p>Una venta en vuelo es una que nuestro stock ya descontó y el POS todavía no tiene: igualar ese producto al
 * POS devolvería unidades vendidas. Por eso los casos de abajo cubren las dos formas que tiene (una venta sin
 * entregar al POS, una orden cobrada con el stock aún sin descontar) y la carrera que queda (una venta que entra
 * mientras se lee el POS).
 *
 * <p>El POS es un {@code @MockitoBean}, como en {@code OutboxCrashRecoveryTest}, y responde solo los ids que se le
 * piden. Lotes de 2 para que el recorrido por lotes se ejercite con pocos productos.
 */
@WithPosIntegration
@TestPropertySource(properties = "app.pos.reconciliation.batch-size=2")
class PosReconciliationTest extends IntegrationTestSupport {

    @MockitoBean
    private PosClient posClient;

    @Autowired
    private PosReconciliationService reconciliation;
    @Autowired
    private PosConnectionRepository connectionRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private SyncWebHookLogRepository webhookLogRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxMessageRepository outboxRepository;

    private Store store;
    private Category category;
    private PosConnection connection;

    @BeforeEach
    void posStore() {
        store = createStore("pos4");
        setSetting(store, "pos_sync_enabled", "true");
        category = createCategory(store, "POS");
        connection = connection(store);
    }

    // --- stock ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("Una divergencia de stock se corrige hacia el POS y queda registrada")
    void aStockDivergenceIsCorrectedTowardsThePos() {
        Mapped mapped = mapped(10, 9990, 1);
        posSays(row(mapped, 1, 7, 9990));

        PosReconciliationSummary summary = reconciliation.reconcileStore(store);

        assertThat(stock(mapped)).isEqualTo(7);
        assertThat(summary.checked()).isEqualTo(1);
        assertThat(summary.stockCorrected()).isEqualTo(1);
        SyncWebhookLog log = reconciliationLog(connection);
        assertThat(log.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
        assertThat(log.getPayload()).containsEntry("trigger", "manual").containsEntry("stock_corrected", 1);
    }

    @Test
    @DisplayName("Un producto con una venta todavía sin entregar al POS no se corrige")
    void aProductWithAPendingPosSaleIsNotCorrected() {
        Mapped mapped = mapped(9, 9990, 1);
        // La venta ya descontó 1 aquí (10 → 9) y el POS todavía no la tiene: dice 10.
        saleOf(mapped, true, OutboxStatus.PENDING);
        posSays(row(mapped, 1, 10, 9990));

        PosReconciliationSummary summary = reconciliation.reconcileStore(store);

        assertThat(stock(mapped)).as("igualarlo al POS devolvería la unidad vendida").isEqualTo(9);
        assertThat(summary.pendingInFlight()).isEqualTo(1);
        assertThat(summary.stockCorrected()).isZero();
    }

    @Test
    @DisplayName("Una venta que falló al llegar al POS también bloquea la corrección")
    void aFailedPosSaleAlsoBlocksTheCorrection() {
        Mapped mapped = mapped(9, 9990, 1);
        saleOf(mapped, true, OutboxStatus.FAILED);
        posSays(row(mapped, 1, 10, 9990));

        PosReconciliationSummary summary = reconciliation.reconcileStore(store);

        assertThat(stock(mapped)).isEqualTo(9);
        assertThat(summary.pendingInFlight()).isEqualTo(1);
    }

    @Test
    @DisplayName("Una orden cobrada con el stock aún sin descontar bloquea la corrección")
    void aPaidOrderWhoseStockIsNotAppliedYetBlocksTheCorrection() {
        Mapped mapped = mapped(10, 9990, 1);
        // El outbox ya la entregó y el POS la descontó (dice 9), pero aquí el descuento todavía no corrió. Si se
        // igualara a 9, el descuento pendiente dejaría 8.
        saleOf(mapped, false, OutboxStatus.SENT);
        posSays(row(mapped, 1, 9, 9990));

        PosReconciliationSummary summary = reconciliation.reconcileStore(store);

        assertThat(stock(mapped)).isEqualTo(10);
        assertThat(summary.pendingInFlight()).isEqualTo(1);
    }

    @Test
    @DisplayName("Una venta que el POS ya tiene no bloquea nada")
    void aSaleAlreadySentToThePosDoesNotBlock() {
        Mapped mapped = mapped(9, 9990, 1);
        saleOf(mapped, true, OutboxStatus.SENT);
        // El POS tiene la venta y además vendió una en el mostrador cuyo evento se perdió.
        posSays(row(mapped, 1, 8, 9990));

        PosReconciliationSummary summary = reconciliation.reconcileStore(store);

        assertThat(stock(mapped)).isEqualTo(8);
        assertThat(summary.stockCorrected()).isEqualTo(1);
    }

    @Test
    @DisplayName("Una venta web que entra mientras se lee el POS no se pisa")
    void aWebSaleDuringThePosReadIsNotOverwritten() {
        Mapped mapped = mapped(10, 9990, 1);
        // El POS respondió 7 a partir de una lectura anterior a esta venta, que no conoce.
        posAnswers(ids -> {
            productRepository.decrementStock(mapped.product.getId(), 1);
            return snapshot(row(mapped, 1, 7, 9990));
        });

        PosReconciliationSummary summary = reconciliation.reconcileStore(store);

        assertThat(stock(mapped)).as("la venta sobrevive; el producto queda para la próxima pasada").isEqualTo(9);
        assertThat(summary.changedMeanwhile()).isEqualTo(1);
        assertThat(summary.stockCorrected()).isZero();
    }

    // --- catálogo -------------------------------------------------------------------------------------

    @Test
    @DisplayName("Un precio distinto con la misma versión se corrige: lo que se movió es lo de aquí")
    void aPriceDivergenceIsCorrectedEvenWithTheSameVersion() {
        Mapped mapped = mapped(10, 9990, 5);
        posSays(row(mapped, 5, 10, 12990));

        PosReconciliationSummary summary = reconciliation.reconcileStore(store);

        assertThat(price(mapped)).isEqualByComparingTo("12990");
        assertThat(summary.catalogCorrected()).isEqualTo(1);
    }

    @Test
    @DisplayName("Una lectura más vieja que un evento ya aplicado no lo deshace")
    void anOlderSnapshotDoesNotRevertANewerEvent() {
        Mapped mapped = mapped(10, 15000, 12);
        posSays(row(mapped, 11, 10, 9990));

        PosReconciliationSummary summary = reconciliation.reconcileStore(store);

        assertThat(price(mapped)).isEqualByComparingTo("15000");
        assertThat(summary.catalogCorrected()).isZero();
        assertThat(mappingRepository.findById(mapped.mapping.getId()).orElseThrow().getCatalogVersion())
                .isEqualTo(12L);
    }

    @Test
    @DisplayName("Lo que el mapeo no sincroniza no se corrige")
    void theFlagsAreRespected() {
        Mapped mapped = mapped(10, 9990, 1);
        mapped.mapping.setSyncStock(false);
        mapped.mapping.setSyncPrice(false);
        mappingRepository.save(mapped.mapping);
        posSays(row(mapped, 1, 3, 1));

        PosReconciliationSummary summary = reconciliation.reconcileStore(store);

        assertThat(stock(mapped)).isEqualTo(10);
        assertThat(price(mapped)).isEqualByComparingTo("9990");
        assertThat(summary.stockCorrected()).isZero();
        assertThat(summary.catalogCorrected()).isZero();
    }

    // --- lo que no se puede conciliar --------------------------------------------------------------

    @Test
    @DisplayName("Un producto que el POS no devuelve se reporta")
    void aProductMissingInThePosIsReported() {
        Mapped mapped = mapped(10, 9990, 1);
        posSays();

        PosReconciliationSummary summary = reconciliation.reconcileStore(store);

        assertThat(summary.missingInPos()).isEqualTo(1);
        assertThat(stock(mapped)).isEqualTo(10);
        assertThat(reconciliationLog(connection).getPayload())
                .containsEntry("missing_in_pos_ids", List.of(mapped.product.getId()));
    }

    @Test
    @DisplayName("Los mapeos pausados y las tiendas con el POS apagado no se concilian")
    void pausedMappingsAndStoresWithThePosOffAreSkipped() {
        Mapped paused = mapped(10, 9990, 1);
        paused.mapping.setSyncStatus(SyncStatus.PAUSED);
        mappingRepository.save(paused.mapping);
        posSays(row(paused, 1, 3, 9990));

        assertThat(reconciliation.reconcileStore(store).checked()).isZero();
        assertThat(stock(paused)).isEqualTo(10);

        Store off = createStore("pos4-off");
        PosConnection offConnection = connection(off);
        assertThatThrownBy(() -> reconciliation.reconcileStore(off))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("apagada");

        reconciliation.reconcileAll();
        verify(posClient, never()).readCatalog(argThat(c -> c != null
                && offConnection.getCompanyId().equals(c.getCompanyId())), any());
    }

    @Test
    @DisplayName("Si la lectura de una empresa falla, se reporta y las demás siguen")
    void aFailedReadIsReportedAndTheOtherCompaniesContinue() {
        PosConnection broken = connection(store);
        Mapped ofBroken = mapped(broken, 10, 9990, 1);
        Mapped ofWorking = mapped(10, 9990, 1);
        Map<Long, PosCatalogSnapshot.Row> working = Map.of(ofWorking.externalId, row(ofWorking, 1, 4, 9990));
        when(posClient.readCatalog(any(), any())).thenAnswer(invocation -> {
            PosConnection asked = invocation.getArgument(0);
            return asked.getCompanyId().equals(broken.getCompanyId())
                    ? PosCatalogSnapshot.failed("El POS respondió HTTP 503 a la lectura del catálogo")
                    : new PosCatalogSnapshot(working, null);
        });

        PosReconciliationSummary summary = reconciliation.reconcileStore(store);

        assertThat(stock(ofWorking)).isEqualTo(4);
        assertThat(stock(ofBroken)).isEqualTo(10);
        assertThat(summary.failedConnections()).isEqualTo(1);
        assertThat(summary.errors()).anyMatch(error -> error.contains("HTTP 503"));
        assertThat(reconciliationLog(broken).getStatus()).isEqualTo(WebhookStatus.FAILED);
    }

    @Test
    @DisplayName("Se recorren todos los lotes")
    void allBatchesAreReconciled() {
        Mapped first = mapped(10, 9990, 1);
        Mapped second = mapped(10, 9990, 1);
        Mapped third = mapped(10, 9990, 1);
        posSays(row(first, 1, 1, 9990), row(second, 1, 2, 9990), row(third, 1, 3, 9990));

        PosReconciliationSummary summary = reconciliation.reconcileStore(store);

        assertThat(List.of(stock(first), stock(second), stock(third))).containsExactly(1, 2, 3);
        assertThat(summary.checked()).isEqualTo(3);
        verify(posClient, times(2)).readCatalog(any(), any());
    }

    // --- el botón del panel --------------------------------------------------------------------------

    @Test
    @DisplayName("Resincronizar pide settings.manage")
    void resyncRequiresSettingsManage() throws Exception {
        String viewer = loginAdmin(store, createAdminWithPermissions(store, "pos4-view", "settings.view"));

        mockMvc.perform(post("/api/admin/pos/resync")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + viewer))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Con la sincronización apagada en la tienda, resincronizar se rechaza con un motivo")
    void resyncIsRejectedWhenThePosIsOffForTheStore() throws Exception {
        // Con conexión y token, pero sin el sí de la tienda.
        Store off = createStore("pos4-off");
        connection(off);
        String admin = loginAdmin(off, createAdminWithPermissions(off, "pos4-manage", "settings.manage"));

        mockMvc.perform(post("/api/admin/pos/resync")
                        .header("Host", hostHeader(off))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("apagada")));
    }

    @Test
    @DisplayName("Resincronizar devuelve el resumen")
    void resyncReturnsTheSummary() throws Exception {
        Mapped mapped = mapped(10, 9990, 1);
        posSays(row(mapped, 1, 6, 9990));
        String admin = loginAdmin(store, createAdminWithPermissions(store, "pos4-manage", "settings.manage"));

        mockMvc.perform(post("/api/admin/pos/resync")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checked").value(1))
                .andExpect(jsonPath("$.stockCorrected").value(1))
                .andExpect(jsonPath("$.failedConnections").value(0));

        assertThat(stock(mapped)).isEqualTo(6);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Mapped(Product product, ProductSyncMapping mapping, long externalId) {
    }

    private PosConnection connection(Store target) {
        PosConnection created = new PosConnection();
        created.setStore(target);
        created.setCompanyName("Empresa POS");
        created.setCompanyId(nextSeq());
        created.setApiUrl("https://pos.test.local/api");
        created.setApiKey("k");
        created.setWebhookSecret("s");
        created.setActive(true);
        return connectionRepository.save(created);
    }

    private Mapped mapped(int stock, long price, long version) {
        return mapped(connection, stock, price, version);
    }

    private Mapped mapped(PosConnection owner, int stock, long price, long version) {
        Product product = createProduct(owner.getStore(), category, "Producto POS", BigDecimal.valueOf(price));
        product.setStock(stock);
        productRepository.save(product);

        long externalId = nextSeq();
        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setExternalId(externalId);
        mapping.setExternalSku(product.getSku());
        mapping.setCompanyId(owner.getCompanyId());
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncDirection(SyncDirection.FROM_POS);
        mapping.setSyncStock(true);
        mapping.setSyncPrice(true);
        mapping.setSyncName(false);
        mapping.setSyncDescription(false);
        mapping.setCatalogVersion(version);
        mapping.setLastSyncedAt(Instant.now());
        mappingRepository.save(mapping);
        return new Mapped(product, mapping, externalId);
    }

    /** Una venta web de una unidad del producto, cobrada, con su fila POS_ORDER en el estado dado. */
    private void saleOf(Mapped mapped, boolean stockApplied, OutboxStatus outboxStatus) {
        long seq = nextSeq();
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-POS4-" + seq);
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(mapped.product.getPrice());
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(mapped.product.getPrice());
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        order.setStockApplied(stockApplied);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(mapped.product);
        item.setProductName(mapped.product.getName());
        item.setProductSku(mapped.product.getSku());
        item.setQuantity(1);
        item.setPrice(mapped.product.getPrice());
        item.setSubtotal(mapped.product.getPrice());
        item.setTaxAmount(BigDecimal.ZERO);
        item.setStockApplied(stockApplied);
        order.setItems(new ArrayList<>(List.of(item)));
        orderRepository.save(order);

        OutboxMessage message = new OutboxMessage();
        message.setOrder(order);
        message.setType(OutboxType.POS_ORDER);
        message.setCompanyId(mapped.mapping.getCompanyId());
        message.setDedupKey("pos4-test:" + seq);
        message.setStatus(outboxStatus);
        message.setNextAttemptAt(Instant.now());
        message.setCreatedAt(Instant.now());
        outboxRepository.save(message);
    }

    private static PosCatalogSnapshot.Row row(Mapped mapped, long version, int stockWeb, long price) {
        return new PosCatalogSnapshot.Row(mapped.externalId, version, stockWeb, BigDecimal.valueOf(price), null, null);
    }

    private static PosCatalogSnapshot snapshot(PosCatalogSnapshot.Row... rows) {
        return new PosCatalogSnapshot(Arrays.stream(rows)
                .collect(Collectors.toMap(PosCatalogSnapshot.Row::productId, Function.identity())), null);
    }

    /** El POS responde lo que tiene de los ids que se le piden, como haría con lotes. */
    private void posSays(PosCatalogSnapshot.Row... rows) {
        Map<Long, PosCatalogSnapshot.Row> all = snapshot(rows).rows();
        posAnswers(ids -> {
            Map<Long, PosCatalogSnapshot.Row> asked = new HashMap<>();
            ids.stream().filter(all::containsKey).forEach(id -> asked.put(id, all.get(id)));
            return new PosCatalogSnapshot(asked, null);
        });
    }

    private void posAnswers(Function<Collection<Long>, PosCatalogSnapshot> answer) {
        when(posClient.readCatalog(any(), any())).thenAnswer(invocation -> answer.apply(invocation.getArgument(1)));
    }

    private int stock(Mapped mapped) {
        return productRepository.findById(mapped.product.getId()).orElseThrow().getStock();
    }

    private BigDecimal price(Mapped mapped) {
        return productRepository.findById(mapped.product.getId()).orElseThrow().getPrice();
    }

    private SyncWebhookLog reconciliationLog(PosConnection owner) {
        return webhookLogRepository.findTop50ByCompanyIdOrderByCreatedAtDesc(owner.getCompanyId()).stream()
                .filter(log -> "pos.reconciliation".equals(log.getEventType()))
                .findFirst()
                .orElseThrow();
    }
}
