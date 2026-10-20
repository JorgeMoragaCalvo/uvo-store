package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.service.outbox.OutboxResult;
import org.uvo.uvostore.service.outbox.PosSaleOutboxHandler;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosIntegrationPolicy;
import org.uvo.uvostore.service.pos.PosOrderNotifier;
import org.uvo.uvostore.service.pos.PosOrderPayloadMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PROD-12. Con la integración apagada no se registra ninguna venta, y eso significa que no se emite ningún
 * documento tributario.
 *
 * <p>PROD-13b: el corte está en las dos puntas del outbox. Al confirmar el pago no se encola nada
 * ({@code companiesToNotify} vacío), y si la integración se apaga con filas ya encoladas, al despacharlas se
 * omiten sin tocar el cliente del POS.
 *
 * <p>Los dos casos son las dos mitades de la política, y la segunda es la que hasta PROD-12 no existía:
 * {@code pos_sync_enabled} llevaba desde el principio en el panel sin que lo leyera nadie.
 */
class PosNotifierDisabledTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ProductSyncMappingRepository mappingRepository = mock(ProductSyncMappingRepository.class);
    private final PosConnectionRepository connectionRepository = mock(PosConnectionRepository.class);
    private final PosClient posClient = mock(PosClient.class);

    @Test
    @DisplayName("Con el interruptor de plataforma apagado no se encola ni se envía nada")
    void nothingIsQueuedOrSentWhenThePlatformDisablesIt() {
        assertNothingLeavesWith(PosPolicyFixture.platformDisabled());
    }

    @Test
    @DisplayName("Con la plataforma habilitada pero la tienda sin sincronización, tampoco")
    void nothingIsQueuedOrSentWhenTheStoreDisablesIt() {
        assertNothingLeavesWith(PosPolicyFixture.storeDisabled());
    }

    private void assertNothingLeavesWith(PosIntegrationPolicy policy) {
        PosOrderNotifier notifier = new PosOrderNotifier(orderRepository, mappingRepository, connectionRepository,
                new PosOrderPayloadMapper(), policy, 3);
        Order order = paidOrderWithASyncedItem();

        assertThat(notifier.companiesToNotify(order)).isEmpty();
        // Ni siquiera mira los mapeos: el corte va antes que cualquier lectura.
        verifyNoInteractions(mappingRepository);

        // Una fila encolada antes de apagar la integración.
        OutboxResult result = new PosSaleOutboxHandler(notifier, posClient).handle(rowFor(order, 7L));

        assertThat(result.outcome()).isEqualTo(OutboxResult.Outcome.SKIPPED);
        assertThat(result.detail()).contains("deshabilitada");
        // verifyNoInteractions y no verify(never()).notifyOrder(...): afirma que no se llegó siquiera a mirar
        // el cliente, que es lo que garantiza que no sale ningún documento.
        verifyNoInteractions(posClient);
    }

    private static OutboxMessage rowFor(Order order, Long companyId) {
        return OutboxMessage.builder()
                .id(1L)
                .order(order)
                .type(OutboxType.POS_ORDER)
                .companyId(companyId)
                .dedupKey("pos:" + PosOrderPayloadMapper.idempotencyKey(order.getOrderNumber(), companyId))
                .build();
    }

    /**
     * Una orden que <b>sí</b> tendría que registrarse: pagada y con su producto mapeado a una empresa del
     * POS. Si el corte no estuviera, esto llegaría al cliente — que es lo que hace que la mordida muerda.
     */
    private Order paidOrderWithASyncedItem() {
        Store store = new Store();
        store.setId(1L);
        store.setName("Tienda");
        store.setSlug("tienda");

        Product product = new Product();
        product.setId(10L);
        product.setName("Producto");
        product.setPrice(BigDecimal.valueOf(10000));

        OrderItem item = new OrderItem();
        item.setId(100L);
        item.setProduct(product);
        item.setQuantity(1);
        item.setPrice(BigDecimal.valueOf(10000));

        Order order = new Order();
        order.setId(1L);
        order.setOrderNumber("ORD-PROD12");
        order.setStore(store);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setStatus(OrderStatus.PROCESSING);
        order.setItems(new ArrayList<>(List.of(item)));

        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        return order;
    }
}
