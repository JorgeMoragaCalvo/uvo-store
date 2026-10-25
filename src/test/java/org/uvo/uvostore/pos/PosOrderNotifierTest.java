package org.uvo.uvostore.pos;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ProductVariationSyncMappingRepository;
import org.uvo.uvostore.service.metrics.BusinessMetrics;
import org.uvo.uvostore.service.pos.PosOrderNotifier;
import org.uvo.uvostore.service.pos.PosOrderPayloadMapper;
import org.uvo.uvostore.service.pos.PosSaleRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROD-13b. Las dos mitades del registro de una venta en el POS: a qué empresas va (dentro de la
 * transacción del pago) y qué se le envía a cada una (al despachar la fila del outbox).
 *
 * <p>Unitario porque lo que se comprueba —qué decide el notificador según la orden y los mapeos— no necesita
 * base de datos. El recorrido completo con transacciones de verdad está en {@code OutboxCrashRecoveryTest}.
 */
class PosOrderNotifierTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ProductSyncMappingRepository mappingRepository = mock(ProductSyncMappingRepository.class);
    private final ProductVariationSyncMappingRepository variantMappingRepository =
            mock(ProductVariationSyncMappingRepository.class);
    private final PosConnectionRepository connectionRepository = mock(PosConnectionRepository.class);

    // PROD-12: con la política habilitada. Que no se encole ni se envíe nada cuando está apagada es
    // PosNotifierDisabledTest.
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final PosOrderNotifier notifier = new PosOrderNotifier(
            orderRepository, mappingRepository, variantMappingRepository, connectionRepository, new PosOrderPayloadMapper(),
            PosPolicyFixture.enabled(), 3, new BusinessMetrics(meterRegistry));

    @Test
    @DisplayName("Las empresas salen de los mapeos, sin repetir y ordenadas")
    void companiesComeFromTheMappings() {
        Order order = orderWithTwoItems();
        Product first = order.getItems().get(0).getProduct();
        Product second = order.getItems().get(1).getProduct();
        when(mappingRepository.findByProductIdIn(any())).thenReturn(List.of(
                mapping(second, 2L, 200L), mapping(first, 1L, 100L), mapping(second, 1L, 201L)));

        assertThat(notifier.companiesToNotify(order)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("Un producto mapeado a dos empresas no rompe la confirmación del pago")
    void aProductMappedToTwoCompaniesIsFine() {
        // El caso que tumbaba la transacción del pago: findByProductId devolvía un Optional sobre una
        // columna que no es única y lanzaba IncorrectResultSizeDataAccessException. Ahora es una lista.
        Order order = orderWithOneItem();
        Product product = order.getItems().getFirst().getProduct();
        when(mappingRepository.findByProductIdIn(any())).thenReturn(List.of(
                mapping(product, 1L, 100L), mapping(product, 2L, 200L)));

        assertThat(notifier.companiesToNotify(order)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("Una orden sin productos sincronizados no va a ninguna empresa")
    void anOrderWithNothingToSyncGoesNowhere() {
        Order order = orderWithOneItem();
        when(mappingRepository.findByProductIdIn(any())).thenReturn(List.of());
        when(connectionRepository.existsActiveForStore(anyLong())).thenReturn(false);

        assertThat(notifier.companiesToNotify(order)).isEmpty();
    }

    @Test
    @DisplayName("Al despachar, solo van las líneas de esa empresa, con su id externo")
    void eachCompanyGetsOnlyItsOwnLines() {
        Order order = paid(orderWithTwoItems());
        Product first = order.getItems().get(0).getProduct();
        Product second = order.getItems().get(1).getProduct();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(connectionRepository.findByCompanyId(1L)).thenReturn(Optional.of(connection(1L, true)));
        when(mappingRepository.findByProductIdAndCompanyId(first.getId(), 1L)).thenReturn(Optional.of(mapping(first, 1L, 100L)));
        when(mappingRepository.findByProductIdAndCompanyId(second.getId(), 1L)).thenReturn(Optional.empty());

        PosSaleRequest request = notifier.prepare(order.getId(), 1L);

        assertThat(request.kind()).isEqualTo(PosSaleRequest.Kind.READY);
        assertThat(request.connection().getCompanyId()).isEqualTo(1L);
        assertThat(request.payload().idempotencyKey()).isEqualTo(PosOrderPayloadMapper.idempotencyKey(order.getOrderNumber(), 1L));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) request.payload().body().get("items");
        assertThat(items).singleElement().satisfies(item -> assertThat(item.get("product_id")).isEqualTo(100L));
    }

    @Test
    @DisplayName("Una orden cancelada o reembolsada después del pago no se envía")
    void aSaleUndoneSinceThePaymentIsSkipped() {
        // Entre el pago y el envío pueden pasar horas si el POS está caído. Un documento tributario por una
        // venta que ya se deshizo es justo lo que no puede salir.
        Order cancelled = paid(orderWithOneItem());
        cancelled.setStatus(OrderStatus.CANCELLED);
        when(orderRepository.findById(cancelled.getId())).thenReturn(Optional.of(cancelled));

        PosSaleRequest request = notifier.prepare(cancelled.getId(), 1L);

        assertThat(request.kind()).isEqualTo(PosSaleRequest.Kind.SKIP);
        assertThat(request.reason()).contains("ya no está pagada");
        verify(connectionRepository, never()).findByCompanyId(any());

        Order refunded = paid(orderWithOneItem());
        refunded.setPaymentStatus(PaymentStatus.REFUNDED);
        when(orderRepository.findById(refunded.getId())).thenReturn(Optional.of(refunded));
        assertThat(notifier.prepare(refunded.getId(), 1L).kind()).isEqualTo(PosSaleRequest.Kind.SKIP);
    }

    @Test
    @DisplayName("Sin conexión activa no sale nada, y por eso se puede reintentar")
    void noActiveConnectionIsNotSent() {
        Order order = paid(orderWithOneItem());
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(connectionRepository.findByCompanyId(1L)).thenReturn(Optional.of(connection(1L, false)));

        PosSaleRequest request = notifier.prepare(order.getId(), 1L);

        assertThat(request.kind()).isEqualTo(PosSaleRequest.Kind.NOT_SENT);
        assertThat(request.reason()).contains("company_id=1");
    }

    @Test
    @DisplayName("Si la orden ya no tiene productos de esa empresa, no hay nada que enviar")
    void noLinesForTheCompanyIsSkipped() {
        Order order = paid(orderWithOneItem());
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(connectionRepository.findByCompanyId(1L)).thenReturn(Optional.of(connection(1L, true)));
        when(mappingRepository.findByProductIdAndCompanyId(any(), any())).thenReturn(Optional.empty());

        assertThat(notifier.prepare(order.getId(), 1L).kind()).isEqualTo(PosSaleRequest.Kind.SKIP);
    }

    @Test
    @DisplayName("PROD-15b: una divergencia en la respuesta del POS cuenta solo si supera el umbral, como el aviso")
    void aDivergenceInThePosResponseIsCountedAboveTheThreshold() {
        Product product = new Product();
        product.setId(5L);
        product.setStock(10);
        when(mappingRepository.findByExternalIdAndCompanyId(500L, 1L)).thenReturn(Optional.of(mapping(product, 1L, 500L)));

        // Umbral 3: una diferencia de 1 es lo normal mientras las dos puntas venden a la vez.
        notifier.checkStockDivergence(posResponse(9), 1L);
        assertThat(orderResponseDivergences()).isZero();

        notifier.checkStockDivergence(posResponse(2), 1L);
        assertThat(orderResponseDivergences()).isEqualTo(1);
    }

    private double orderResponseDivergences() {
        return meterRegistry.get(BusinessMetrics.STOCK_DIVERGENCES).tag("source", "order_response").counter().count();
    }

    private static Map<String, Object> posResponse(int newStockWeb) {
        return Map.of("data", Map.of("results", List.of(
                Map.of("success", true, "product_id", 500L, "new_stock_web", newStockWeb))));
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static ProductSyncMapping mapping(Product product, Long companyId, Long externalId) {
        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setCompanyId(companyId);
        mapping.setExternalId(externalId);
        return mapping;
    }

    private static PosConnection connection(Long companyId, boolean active) {
        PosConnection connection = new PosConnection();
        connection.setCompanyId(companyId);
        connection.setApiUrl("https://pos.invalid/api/v1");
        connection.setApiKey("k");
        connection.setActive(active);
        return connection;
    }

    private static Order paid(Order order) {
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setStatus(OrderStatus.PROCESSING);
        return order;
    }

    private static Order orderWithOneItem() {
        Order order = baseOrder();
        order.getItems().add(item(order, 1L, 1000));
        return order;
    }

    private static Order orderWithTwoItems() {
        Order order = baseOrder();
        order.getItems().add(item(order, 1L, 1000));
        order.getItems().add(item(order, 2L, 2000));
        return order;
    }

    private static Order baseOrder() {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNumber("ORD-G2-0001");
        order.setStore(Store.builder().id(1L).name("Tienda").slug("tienda").build());
        order.setSubtotal(new BigDecimal("1000"));
        order.setTotal(new BigDecimal("1000"));
        order.setItems(new ArrayList<>());
        return order;
    }

    private static OrderItem item(Order order, Long productId, int price) {
        Product product = new Product();
        product.setId(productId);
        product.setStock(10);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(1);
        item.setPrice(BigDecimal.valueOf(price));
        item.setSubtotal(BigDecimal.valueOf(price));
        item.setTaxAmount(BigDecimal.ZERO);
        return item;
    }
}
