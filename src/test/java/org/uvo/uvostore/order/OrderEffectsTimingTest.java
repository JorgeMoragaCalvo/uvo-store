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
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.order.AdminOrderService;
import org.uvo.uvostore.service.order.OrderStatusService;
import org.uvo.uvostore.service.order.event.OrderPlacedEvent;
import org.uvo.uvostore.service.order.event.PaymentConfirmedEvent;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

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
 * <p>Se comprueba sobre los eventos publicados y no sobre los oyentes porque los oyentes son
 * {@code AFTER_COMMIT} y bajo la transacción con rollback de {@code IntegrationTestSupport} no llegan
 * a ejecutarse nunca — algo deliberado del andamiaje, no una limitación que convenga sortear aquí. La
 * otra mitad de la cadena, a qué evento está enganchado cada oyente, la fija
 * {@code AsyncListenerDispatchTest}; entre los dos cubren el camino entero.
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

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Un pedido sin pagar no emite documento al POS ni correo de compra")
    void placingAnOrderDoesNotTriggerTheSaleEffects() throws Exception {
        Store store = storeWithProduct();

        checkout(store);

        assertThat(events.stream(OrderPlacedEvent.class))
                .as("el acuse de recibo del pedido sí sale")
                .hasSize(1);
        assertThat(events.stream(PaymentConfirmedEvent.class))
                .as("los efectos de venta (POS y correo de compra) cuelgan de este evento, y no hay pago")
                .isEmpty();
    }

    @Test
    @DisplayName("Al confirmarse el pago sí se disparan")
    void confirmingThePaymentTriggersThem() throws Exception {
        Store store = storeWithProduct();
        Order order = checkout(store);

        orderStatusService.markPaid(order.getId(), "pay_1", order.getTotal());

        assertThat(events.stream(PaymentConfirmedEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("Confirmar dos veces no emite dos documentos")
    void confirmingTwiceDoesNotEmitTwice() throws Exception {
        // Un segundo documento tributario por la misma venta es justamente lo que obliga a tener
        // desactivado el reintento del POS. La guarda de markPaid es lo que lo impide.
        Store store = storeWithProduct();
        Order order = checkout(store);

        orderStatusService.markPaid(order.getId(), "pay_1", order.getTotal());
        orderStatusService.markPaid(order.getId(), "pay_1", order.getTotal());

        assertThat(events.stream(PaymentConfirmedEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("Cancelar un pedido sin pagar no dispara nada")
    void cancellingAnUnpaidOrderTriggersNothing() throws Exception {
        Store store = storeWithProduct();
        Order order = checkout(store);

        orderStatusService.markCancelled(order.getId());

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

        assertThat(events.stream(PaymentConfirmedEvent.class))
                .as("sin este evento no hay ni stock descontado ni documento ni correo")
                .hasSize(1);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
    }

    // --- fixtures ----------------------------------------------------------------------------------

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
