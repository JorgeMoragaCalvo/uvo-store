package org.uvo.uvostore.pos;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.WebhookStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PosConnectionKeyRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.ProductVariationSyncMappingRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.catalog.ProductVariationService;
import org.uvo.uvostore.service.catalog.VariationCommand;
import org.uvo.uvostore.service.pos.PosOrderNotifier;
import org.uvo.uvostore.service.pos.PosSaleRequest;
import org.uvo.uvostore.service.pos.PosSyncService;
import org.uvo.uvostore.service.pos.PosVariantLinkService;
import org.uvo.uvostore.service.pos.PosWebhookService;
import org.uvo.uvostore.service.pos.ProductUpdatePayload;
import org.uvo.uvostore.service.pos.StockUpdatePayload;
import org.uvo.uvostore.service.pos.SyncProductCommand;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.PosSigning;
import org.uvo.uvostore.support.WithPosIntegration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IMP-08. Las variantes con el contrato v1 (§4): el POS no tiene variantes, cada una es un Producto suyo, y la tienda
 * las agrupa. Decisión del usuario: la variación se vincula a mano a su Producto del POS. Desde ahí sus avisos, su
 * stock y su precio van por variante, y la venta manda el Producto del POS de la variante, sin {@code variant_id}.
 */
@WithPosIntegration
class PosVariantsTest extends IntegrationTestSupport {

    @Autowired
    private PosVariantLinkService linkService;
    @Autowired
    private PosWebhookService posWebhookService;
    @Autowired
    private PosSyncService posSyncService;
    @Autowired
    private PosOrderNotifier notifier;
    @Autowired
    private ProductVariationService variationService;
    @Autowired
    private PosConnectionRepository connectionRepository;
    @Autowired
    private PosConnectionKeyRepository keyRepository;
    @Autowired
    private ProductSyncMappingRepository mappingRepository;
    @Autowired
    private ProductVariationSyncMappingRepository variantMappingRepository;
    @Autowired
    private ProductVariationRepository variationRepository;
    @Autowired
    private OrderRepository orderRepository;
    @PersistenceContext
    private EntityManager entityManager;

    private Store store;
    private PosConnection connection;
    private Product product;
    private ProductVariation m;
    private ProductVariation l;

    @BeforeEach
    void posStoreWithAVariableProduct() {
        store = createStore("pos-var");
        setSetting(store, "pos_sync_enabled", "true");
        connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyName("Empresa POS");
        connection.setCompanyId(nextSeq());
        connection.setApiUrl("https://pos.test.local/api");
        connection.setActive(true);
        connection = PosSigning.installKeys(keyRepository, connectionRepository.save(connection));

        product = createProduct(store, createCategory(store, "poleras"), "Polera", BigDecimal.valueOf(9990));
        product.setProductType(ProductType.VARIABLE);
        product = productRepository.save(product);
        m = variation("POL-M-" + nextSeq(), 9990, 4);
        l = variation("POL-L-" + nextSeq(), 10990, 2);
        TenantContext.set(store);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // --- vínculo -----------------------------------------------------------------------------------

    @Test
    @DisplayName("Vincular crea el mapeo del padre, sin external_id, y uno por variante con la empresa")
    void linkingCreatesTheParentMappingAndOnePerVariant() {
        long mPos = nextSeq();
        long lPos = nextSeq();

        linkService.link(product.getId(), m.getId(), mPos);
        linkService.link(product.getId(), l.getId(), lPos);

        var parent = mappingRepository.findByProductIdAndCompanyId(product.getId(), connection.getCompanyId()).orElseThrow();
        assertThat(parent.getExternalId()).as("el padre no es un Producto del POS").isNull();
        assertThat(parent.isSyncName()).as("el nombre del padre es de la tienda").isFalse();
        assertThat(linkService.linksOf(product.getId()))
                .extracting(PosVariantLinkService.Link::posProductId)
                .containsExactlyInAnyOrder(mPos, lPos);
        assertThat(variantMappingRepository.findByCompanyIdAndExternalId(connection.getCompanyId(), mPos))
                .hasValueSatisfying(vm -> assertThat(vm.getVariation().getId()).isEqualTo(m.getId()));
    }

    @Test
    @DisplayName("Un Producto del POS no puede ser dos variantes")
    void aPosProductCannotBeTwoVariants() {
        long pos = nextSeq();
        linkService.link(product.getId(), m.getId(), pos);

        assertThatThrownBy(() -> linkService.link(product.getId(), l.getId(), pos))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("otra variante");
    }

    @Test
    @DisplayName("Ni una variante y un producto simple de la tienda")
    void aPosProductCannotBeAVariantAndASimpleProduct() {
        long pos = nextSeq();
        posSyncService.syncProduct(connection, new SyncProductCommand(pos, "SIMPLE-" + pos, "Taza", null,
                BigDecimal.valueOf(4990), 3, null, null, true, 1, null));

        assertThatThrownBy(() -> linkService.link(product.getId(), m.getId(), pos))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Taza");
    }

    @Test
    @DisplayName("Desvincular la última variante borra el mapeo del padre")
    void unlinkingTheLastVariantRemovesTheParentMapping() {
        linkService.link(product.getId(), m.getId(), nextSeq());

        linkService.unlink(product.getId(), m.getId());

        assertThat(linkService.linksOf(product.getId())).isEmpty();
        assertThat(mappingRepository.findByProductIdAndCompanyId(product.getId(), connection.getCompanyId())).isEmpty();
    }

    // --- avisos por variante -----------------------------------------------------------------------

    @Test
    @DisplayName("stock.updated de una variante mueve esa variación y el padre vuelve a ser la suma")
    void aStockEventMovesTheVariantAndTheParentSum() {
        long mPos = nextSeq();
        linkService.link(product.getId(), m.getId(), mPos);

        SyncWebhookLog log = posWebhookService.handleStockUpdated(stock(mPos, 5, 9));
        fresh();

        assertThat(log.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
        assertThat(variationRepository.findById(m.getId()).orElseThrow().getStock()).isEqualTo(9);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(9 + 2);
    }

    @Test
    @DisplayName("Un aviso con una versión ya aplicada o más vieja se ignora y no pisa nada")
    void aStaleVersionIsIgnored() throws Exception {
        long mPos = nextSeq();
        linkService.link(product.getId(), m.getId(), mPos);
        posWebhookService.handleStockUpdated(stock(mPos, 7, 9));

        SyncWebhookLog stale = posWebhookService.handleStockUpdated(stock(mPos, 6, 1));
        SyncWebhookLog repeated = posWebhookService.handleStockUpdated(stock(mPos, 7, 1));
        fresh();

        assertThat(stale.getStatus()).isEqualTo(WebhookStatus.DISCARDED);
        assertThat(repeated.getStatus()).isEqualTo(WebhookStatus.DISCARDED);
        assertThat(variationRepository.findById(m.getId()).orElseThrow().getStock()).isEqualTo(9);

        // Por la puerta de verdad, el controlador responde 200 con ignored: true (§5).
        mockMvc.perform(PosSigning.signedPost("/api/webhooks/pos/stock-updated", """
                        {"event":"stock.updated","productId":%d,"sku":"POL-M","companyId":%d,"version":3,"stockWeb":1}"""
                        .formatted(mPos, connection.getCompanyId()), connection))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ignored").value(true));
    }

    @Test
    @DisplayName("product.updated de una variante cambia su precio y su exención; el padre toma el más barato")
    void aProductEventUpdatesTheVariantPrice() {
        long lPos = nextSeq();
        linkService.link(product.getId(), l.getId(), lPos);

        posWebhookService.handleProductUpdated(new ProductUpdatePayload(connection.getCompanyId(), lPos, "POL-L", 2,
                Map.of("price", 8990, "tax_exempt", true)));
        fresh();

        ProductVariation updated = variationRepository.findById(l.getId()).orElseThrow();
        assertThat(updated.getPrice()).isEqualByComparingTo("8990");
        assertThat(updated.isTaxExempt()).isTrue();
        assertThat(productRepository.findById(product.getId()).orElseThrow().getPrice()).isEqualByComparingTo("8990");
    }

    @Test
    @DisplayName("§6: con una venta web pendiente de llegar al POS, el stock no se pisa y la versión avanza")
    void aPendingSaleDefersTheStock() {
        long mPos = nextSeq();
        linkService.link(product.getId(), m.getId(), mPos);
        paidOrderWithStockPending(m);

        SyncWebhookLog log = posWebhookService.handleStockUpdated(stock(mPos, 3, 0));
        fresh();

        assertThat(log.getStatus()).isEqualTo(WebhookStatus.SUCCESS);
        assertThat(log.getErrorMessage()).contains("ventas web");
        assertThat(variationRepository.findById(m.getId()).orElseThrow().getStock()).isEqualTo(4);
        assertThat(variantMappingRepository.findByCompanyIdAndVariationId(connection.getCompanyId(), m.getId())
                .orElseThrow().getPosVersion()).isEqualTo(3L);
    }

    // --- panel ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("Una variante vinculada conserva SKU, precio y stock; una sin vincular se edita entera")
    void aLinkedVariantKeepsItsPosFields() {
        linkService.link(product.getId(), m.getId(), nextSeq());

        assertThatThrownBy(() -> variationService.updateVariation(m.getId(),
                new VariationCommand(Map.of(), BigDecimal.valueOf(1), m.getStock(), m.getSku())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("UvoPOS");
        assertThatThrownBy(() -> variationService.deleteVariation(m.getId()))
                .isInstanceOf(BusinessException.class);

        variationService.updateVariation(l.getId(), new VariationCommand(Map.of(), BigDecimal.valueOf(11990),
                l.getStock(), l.getSku()));
        assertThat(variationRepository.findById(l.getId()).orElseThrow().getPrice()).isEqualByComparingTo("11990");
    }

    // --- venta ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("La venta manda el Producto del POS de la variante, sin variant_id")
    void theSaleCarriesThePosProductOfTheVariant() {
        long mPos = nextSeq();
        linkService.link(product.getId(), m.getId(), mPos);
        Order order = paidOrder(m);

        assertThat(notifier.companiesToNotify(order)).containsExactly(connection.getCompanyId());
        PosSaleRequest request = notifier.prepare(order.getId(), connection.getCompanyId());

        assertThat(request.kind()).isEqualTo(PosSaleRequest.Kind.READY);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) request.payload().body().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst())
                .containsEntry("product_id", mPos)
                .containsEntry("quantity", 1)
                .doesNotContainKey("variant_id");
    }

    @Test
    @DisplayName("La respuesta del POS se compara con la variación de ese Producto")
    void theSaleResponseIsComparedWithTheVariation() {
        long lPos = nextSeq();
        linkService.link(product.getId(), l.getId(), lPos);
        Logger notifierLogger = (Logger) LoggerFactory.getLogger(PosOrderNotifier.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        notifierLogger.addAppender(logs);
        try {
            notifier.checkStockDivergence(Map.of("data", Map.of("results", List.of(
                    Map.of("success", true, "product_id", lPos, "new_stock_web", 2)))), connection.getCompanyId());
            assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("Divergencia"));

            notifier.checkStockDivergence(Map.of("data", Map.of("results", List.of(
                    Map.of("success", true, "product_id", lPos, "new_stock_web", 0)))), connection.getCompanyId());
            assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("Divergencia")
                    && event.getFormattedMessage().contains("la variación"));
        } finally {
            notifierLogger.detachAppender(logs);
        }
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private ProductVariation variation(String sku, long price, int stock) {
        ProductVariation variation = new ProductVariation();
        variation.setStore(store);
        variation.setProduct(product);
        variation.setSku(sku);
        variation.setPrice(BigDecimal.valueOf(price));
        variation.setStock(stock);
        variation.setActive(true);
        return variationRepository.save(variation);
    }

    private StockUpdatePayload stock(long posProductId, long version, int stockWeb) {
        return new StockUpdatePayload(connection.getCompanyId(), posProductId, "POL", version, null, null, stockWeb);
    }

    private Order paidOrder(ProductVariation... variations) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-VAR-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(BigDecimal.valueOf(9990L * variations.length));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(order.getSubtotal());
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);
        List<OrderItem> items = new ArrayList<>();
        for (ProductVariation variation : variations) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setVariation(variation);
            item.setProductName(product.getName());
            item.setProductSku(variation.getSku());
            item.setQuantity(1);
            item.setPrice(variation.getPrice());
            item.setSubtotal(variation.getPrice());
            item.setTaxAmount(BigDecimal.ZERO);
            items.add(item);
        }
        order.setItems(items);
        return orderRepository.save(order);
    }

    /** Una orden cobrada cuyo stock todavía no se descontó: cuenta como venta pendiente (§6). */
    private void paidOrderWithStockPending(ProductVariation variation) {
        Order order = paidOrder(variation);
        order.setStockApplied(false);
        orderRepository.save(order);
    }

    private void fresh() {
        entityManager.flush();
        entityManager.clear();
    }
}
