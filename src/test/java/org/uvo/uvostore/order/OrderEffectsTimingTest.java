package org.uvo.uvostore.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.order.AdminOrderService;
import org.uvo.uvostore.service.order.OrderStatusService;
import org.uvo.uvostore.service.order.event.PaymentConfirmedEvent;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F07. Cuándo se disparan los efectos de una venta.
 *
 * <p>El fallo: el checkout publicaba {@code OrderCompletedEvent} al <b>crear</b> la orden, con el pago
 * pendiente, y de ese evento colgaban la notificación al POS —que emite un documento tributario— y el
 * correo de "Gracias por tu compra". Un checkout abandonado emitía la boleta de una venta que no
 * ocurrió y le daba las gracias a quien no había pagado.
 *
 * <p>PROD-13b: los efectos que salen a la red ya no cuelgan de eventos sino del outbox, escritos en la misma
 * transacción, así que esto se comprueba sobre las filas que deja cada paso. Del evento de pago confirmado
 * sigue colgando el descuento de stock, y por eso se sigue mirando también.
 */
@RecordApplicationEvents
class OrderEffectsTimingTest extends IntegrationTestSupport {

    @Autowired
    private ApplicationEvents events;
    @Autowired
    private OrderStatusService orderStatusService;
    @Autowired
    private AdminOrderService adminOrderService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OutboxMessageRepository outboxRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Un pedido sin pagar solo encola el acuse de recibo: ni venta al POS ni correo de compra")
    void placingAnOrderDoesNotTriggerTheSaleEffects() throws Exception {
        Store store = storeWithProduct();

        Order order = checkout(store);

        assertThat(types(order))
                .as("el acuse de recibo del pedido sí sale; la venta y el correo de compra esperan al pago")
                .containsExactly(OutboxType.ORDER_PLACED_EMAIL);
        assertThat(events.stream(PaymentConfirmedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("Al confirmarse el pago se encola el correo de compra")
    void confirmingThePaymentTriggersThem() throws Exception {
        Store store = storeWithProduct();
        Order order = checkout(store);

        orderStatusService.markPaid(order.getId(), "pay_1", order.getTotal());

        assertThat(types(order)).containsExactly(OutboxType.ORDER_PLACED_EMAIL, OutboxType.ORDER_CONFIRMATION_EMAIL);
        assertThat(events.stream(PaymentConfirmedEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("Confirmar dos veces no encola dos veces")
    void confirmingTwiceDoesNotEmitTwice() throws Exception {
        // Un segundo documento tributario por la misma venta es justamente lo que obliga a tener
        // desactivado el reintento del POS. La guarda de markPaid es lo que lo impide.
        Store store = storeWithProduct();
        Order order = checkout(store);

        orderStatusService.markPaid(order.getId(), "pay_1", order.getTotal());
        orderStatusService.markPaid(order.getId(), "pay_1", order.getTotal());

        assertThat(types(order)).containsOnlyOnce(OutboxType.ORDER_CONFIRMATION_EMAIL);
        assertThat(events.stream(PaymentConfirmedEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("Cancelar un pedido sin pagar no encola nada más")
    void cancellingAnUnpaidOrderTriggersNothing() throws Exception {
        Store store = storeWithProduct();
        Order order = checkout(store);

        orderStatusService.markCancelled(order.getId());

        assertThat(types(order)).containsExactly(OutboxType.ORDER_PLACED_EMAIL);
        assertThat(events.stream(PaymentConfirmedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("El pago manual confirmado desde el panel también los dispara")
    void confirmingAManualPaymentFromTheAdminPanelTriggersThem() throws Exception {
        // La transferencia es el método principal de muchas tiendas y se confirma a mano. Antes
        // updatePaymentStatus solo escribía la columna: la orden quedaba PAID sin correo, sin boleta
        // y —esto no estaba en la auditoría— sin descontar stock, porque el listener de stock también
        // espera este evento.
        Store store = storeWithProduct();
        Order order = checkout(store);
        TenantContext.set(store);

        adminOrderService.updatePaymentStatus(order.getId(), "PAID");

        assertThat(types(order)).contains(OutboxType.ORDER_CONFIRMATION_EMAIL);
        assertThat(events.stream(PaymentConfirmedEvent.class))
                .as("sin este evento no hay stock descontado")
                .hasSize(1);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private List<OutboxType> types(Order order) {
        return outboxRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .map(OutboxMessage::getType)
                .toList();
    }

    private Store storeWithProduct() {
        Store store = createStore("effects");
        disableShipping(store);
        product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        return store;
    }

    private Product product;

    private Order checkout(Store store) throws Exception {
        String body = """
                {
                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [{"id":%d,"type":"product","quantity":1}],
                  "paymentMethod": "manual"
                }
                """.formatted(product.getId());

        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return orderRepository.findById(objectMapper.readTree(response).get("orderId").asLong()).orElseThrow();
    }
}
