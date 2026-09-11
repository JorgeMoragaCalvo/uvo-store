package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosOrderNotifier;
import org.uvo.uvostore.service.pos.PosOrderPayload;
import org.uvo.uvostore.service.pos.PosOrderPayloadMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G2. {@code Order.posSynced} y {@code Order.syncAttempts} existían en la tabla y los inicializaba
 * el checkout, pero ningún código los leía ni los actualizaba: una notificación al POS que fallaba
 * no dejaba más rastro que una línea de log, así que no había forma de saber qué órdenes no llegaron
 * a la plataforma SII ni de reintentarlas.
 *
 * <p>Test unitario y no de integración porque {@code notifyOrder} es {@code REQUIRES_NEW}: bajo la
 * transacción con rollback de {@code IntegrationTestSupport} abriría una transacción nueva que no
 * vería las fixtures. Lo que se comprueba aquí —qué se escribe en la orden según cómo responda el
 * POS— no necesita base de datos.
 */
class PosOrderNotifierTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ProductSyncMappingRepository mappingRepository = mock(ProductSyncMappingRepository.class);
    private final PosConnectionRepository connectionRepository = mock(PosConnectionRepository.class);
    private final PosClient posClient = mock(PosClient.class);

    private final PosOrderNotifier notifier = new PosOrderNotifier(
            orderRepository, mappingRepository, connectionRepository, posClient, new PosOrderPayloadMapper(), 3);

    @Test
    @DisplayName("Una notificación correcta marca la orden como sincronizada y cuenta el intento")
    void aSuccessfulNotificationIsRecordedOnTheOrder() {
        Order order = orderWithOneItem();
        givenOneSyncedProduct(order, 42L);
        when(posClient.notifyOrder(any(), any(PosOrderPayload.class))).thenReturn(Map.of("ok", true));

        boolean result = notifier.notifyOrder(order.getId());

        assertThat(result).isTrue();
        assertThat(order.isPosSynced()).isTrue();
        assertThat(order.getSyncAttempts()).isEqualTo(1);
        assertThat(order.getLastSyncError()).isNull();
    }

    @Test
    @DisplayName("Si el POS falla, la orden queda sin sincronizar, con el intento contado y el error escrito")
    void aFailedNotificationLeavesTheOrderPending() {
        Order order = orderWithOneItem();
        givenOneSyncedProduct(order, 42L);
        when(posClient.notifyOrder(any(), any(PosOrderPayload.class)))
                .thenThrow(new RuntimeException("502 Bad Gateway"));

        boolean result = notifier.notifyOrder(order.getId());

        // Esto es lo que hace posible el reintento: sin syncAttempts ni posSynced escritos, la orden
        // era indistinguible de una que nunca tuvo nada que notificar.
        assertThat(result).isFalse();
        assertThat(order.isPosSynced()).isFalse();
        assertThat(order.getSyncAttempts()).isEqualTo(1);
        assertThat(order.getLastSyncError()).contains("502 Bad Gateway");
    }

    @Test
    @DisplayName("Cada intento suma: dos fallos seguidos dejan syncAttempts en 2")
    void everyAttemptIsCounted() {
        Order order = orderWithOneItem();
        givenOneSyncedProduct(order, 42L);
        when(posClient.notifyOrder(any(), any(PosOrderPayload.class)))
                .thenThrow(new RuntimeException("timeout"));

        notifier.notifyOrder(order.getId());
        notifier.notifyOrder(order.getId());

        // El tope de reintentos del job se apoya en esta cuenta; si no subiera, reintentaría para
        // siempre una orden que nunca va a entrar.
        assertThat(order.getSyncAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("Una orden sin productos sincronizados no cuenta intentos: no hay nada que reintentar")
    void anOrderWithNothingToSyncNeverCountsAnAttempt() {
        Order order = orderWithOneItem();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(mappingRepository.findByProductId(any())).thenReturn(Optional.empty());
        when(connectionRepository.findByStoreId(any())).thenReturn(Optional.empty());

        boolean result = notifier.notifyOrder(order.getId());

        // syncAttempts en 0 es justo lo que mantiene estas órdenes fuera del job de reintento.
        assertThat(result).isTrue();
        assertThat(order.getSyncAttempts()).isZero();
    }

    @Test
    @DisplayName("Con dos empresas, si una falla la orden NO se da por sincronizada")
    void anOrderIsOnlySyncedWhenEveryCompanyAnswered() {
        Order order = orderWithTwoItems();
        Product first = order.getItems().get(0).getProduct();
        Product second = order.getItems().get(1).getProduct();

        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(mappingRepository.findByProductId(first.getId())).thenReturn(Optional.of(mapping(first, 1L, 100L)));
        when(mappingRepository.findByProductId(second.getId())).thenReturn(Optional.of(mapping(second, 2L, 200L)));
        when(mappingRepository.findByProductIdAndCompanyId(first.getId(), 1L)).thenReturn(Optional.of(mapping(first, 1L, 100L)));
        when(mappingRepository.findByProductIdAndCompanyId(second.getId(), 2L)).thenReturn(Optional.of(mapping(second, 2L, 200L)));
        when(mappingRepository.findByProductIdAndCompanyId(first.getId(), 2L)).thenReturn(Optional.empty());
        when(mappingRepository.findByProductIdAndCompanyId(second.getId(), 1L)).thenReturn(Optional.empty());
        when(connectionRepository.findByCompanyId(1L)).thenReturn(Optional.of(connection(1L)));
        when(connectionRepository.findByCompanyId(2L)).thenReturn(Optional.of(connection(2L)));
        when(connectionRepository.findByStoreId(any())).thenReturn(Optional.of(connection(1L)));
        when(posClient.notifyOrder(any(), any(PosOrderPayload.class)))
                .thenReturn(Map.of("ok", true))
                .thenThrow(new RuntimeException("la segunda empresa no responde"));

        boolean result = notifier.notifyOrder(order.getId());

        // Darla por sincronizada con la primera dejaría a la segunda empresa sin su documento y
        // fuera del reintento, que es exactamente la pérdida silenciosa que G2 viene a cerrar.
        assertThat(result).isFalse();
        assertThat(order.isPosSynced()).isFalse();
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private void givenOneSyncedProduct(Order order, Long companyId) {
        Product product = order.getItems().getFirst().getProduct();
        ProductSyncMapping mapping = mapping(product, companyId, 999L);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(mappingRepository.findByProductId(product.getId())).thenReturn(Optional.of(mapping));
        when(mappingRepository.findByProductIdAndCompanyId(product.getId(), companyId)).thenReturn(Optional.of(mapping));
        when(connectionRepository.findByCompanyId(companyId)).thenReturn(Optional.of(connection(companyId)));
        when(connectionRepository.findByStoreId(any())).thenReturn(Optional.of(connection(companyId)));
    }

    private static ProductSyncMapping mapping(Product product, Long companyId, Long externalId) {
        ProductSyncMapping mapping = new ProductSyncMapping();
        mapping.setProduct(product);
        mapping.setCompanyId(companyId);
        mapping.setExternalId(externalId);
        return mapping;
    }

    private static PosConnection connection(Long companyId) {
        PosConnection connection = new PosConnection();
        connection.setCompanyId(companyId);
        connection.setApiUrl("https://pos.invalid/api/v1");
        connection.setApiKey("k");
        connection.setActive(true);
        return connection;
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
