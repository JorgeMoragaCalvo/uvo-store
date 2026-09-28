package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F14. Lo que el panel puede y no puede hacerle a una orden.
 *
 * <p>Los endpoints de estado eran setters sin precondiciones, así que desde el panel se podía despachar un
 * pedido impago, revivir una orden cancelada cuyo stock ya se había devuelto —y eso no tiene vuelta atrás,
 * porque {@code OrderInventoryService} bloquea reaplicar un stock restaurado— y degradar una orden pagada
 * a FAILED.
 *
 * <p>Lo último es lo que más importa y no estaba en el hallazgo: es el mismo daño que F03 cerró para los
 * webhooks tardíos, pero por la otra puerta. La guarda vive en {@code OrderStatusService} y esta ruta la
 * esquivaba escribiendo la columna a pelo. {@code OrderPaymentTransitionsTest} fija esa invariante desde
 * el lado de los webhooks; esto la fija desde el panel.
 */
class AdminOrderTransitionsTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private org.uvo.uvostore.service.order.OrderInventoryService orderInventoryService;

    @Test
    @DisplayName("El panel no puede degradar una orden pagada a fallida")
    void thePanelCannotDowngradeAPaidOrder() throws Exception {
        Fixture f = paidOrder();

        mockMvc.perform(paymentStatus(f, "FAILED")).andExpect(status().isOk());

        // markPaymentFailed ignora la petición por la guarda de F03 y deja constancia; lo que no puede
        // pasar es que la orden deje de estar pagada.
        assertThat(orderRepository.findById(f.orderId).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("Marcar fallido un pago que sí está pendiente sigue funcionando")
    void failingAGenuinelyPendingPaymentStillWorks() throws Exception {
        // La guarda no puede haber convertido el endpoint en un no-op: un pago que no llegó se marca.
        Fixture f = pendingOrder();

        mockMvc.perform(paymentStatus(f, "FAILED")).andExpect(status().isOk());

        assertThat(orderRepository.findById(f.orderId).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("Volver a 'pendiente de pago' a mano se rechaza")
    void movingPaymentBackToPendingIsRefused() throws Exception {
        Fixture f = paidOrder();

        mockMvc.perform(paymentStatus(f, "PENDING")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("No se despacha un pedido impago, ni por envío ni por seguimiento")
    void anUnpaidOrderCannotBeDispatched() throws Exception {
        Fixture f = pendingOrder();

        mockMvc.perform(post("/api/admin/orders/" + f.orderId + "/mark-shipped")
                        .header("Host", hostHeader(f.store)).header("Authorization", "Bearer " + f.token))
                .andExpect(status().isBadRequest());

        // El endpoint de seguimiento también pone SHIPPED: sin su propia comprobación, la de mark-shipped
        // se saltaba guardando un número de seguimiento.
        mockMvc.perform(post("/api/admin/orders/" + f.orderId + "/tracking")
                        .header("Host", hostHeader(f.store)).header("Authorization", "Bearer " + f.token)
                        .contentType("application/json").content("{\"trackingNumber\":\"TRACK-1\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/admin/orders/" + f.orderId + "/status")
                        .header("Host", hostHeader(f.store)).header("Authorization", "Bearer " + f.token)
                        .contentType("application/json").content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Pagada sí se despacha: preparar el pedido nunca se bloqueó")
    void aPaidOrderCanBeDispatchedAndProcessingIsAlwaysAllowed() throws Exception {
        Fixture paid = paidOrder();
        mockMvc.perform(post("/api/admin/orders/" + paid.orderId + "/mark-shipped")
                        .header("Host", hostHeader(paid.store)).header("Authorization", "Bearer " + paid.token))
                .andExpect(status().isOk());

        // mark-processing se deja libre a propósito: preparar el pedido mientras se espera la
        // transferencia es legítimo y no mueve dinero ni stock.
        Fixture pending = pendingOrder();
        mockMvc.perform(post("/api/admin/orders/" + pending.orderId + "/mark-processing")
                        .header("Host", hostHeader(pending.store)).header("Authorization", "Bearer " + pending.token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Una orden cancelada que ya devolvió su stock no revive")
    void aCancelledOrderThatReturnedItsStockCannotBeRevived() throws Exception {
        // El ciclo descontar/devolver se recorre con OrderInventoryService y no cancelando por el panel,
        // porque el descuento cuelga de un listener AFTER_COMMIT y bajo la transacción con rollback de
        // IntegrationTestSupport esos listeners nunca llegan a correr (es deliberado del andamiaje). Lo que
        // importa aquí es la invariante: con el stock ya devuelto, la orden no puede volver a estar viva.
        Fixture f = paidOrder();
        Order order = orderRepository.findById(f.orderId).orElseThrow();
        orderInventoryService.applyOrderStock(order);
        int stockAfterSale = reloadStock(f.product);
        orderInventoryService.restoreOrderStock(order);

        assertThat(reloadStock(f.product)).as("al devolverlo, el stock vuelve").isGreaterThan(stockAfterSale);
        int stockAfterRestore = reloadStock(f.product);
        assertThat(orderRepository.findById(f.orderId).orElseThrow().isStockRestored()).isTrue();

        mockMvc.perform(put("/api/admin/orders/" + f.orderId + "/status")
                        .header("Host", hostHeader(f.store)).header("Authorization", "Bearer " + f.token)
                        .contentType("application/json").content("{\"status\":\"PROCESSING\"}"))
                .andExpect(status().isBadRequest());

        assertThat(reloadStock(f.product))
                .as("y el stock no se toca: reaplicarlo es imposible, así que revivirla descuadraría el inventario")
                .isEqualTo(stockAfterRestore);
    }

    @Test
    @DisplayName("El estado de pago no se cambia por el endpoint de estado de la orden")
    void theOrderStatusEndpointRefusesPaymentStates() throws Exception {
        // OrderStatus.PAID existe en el enum y no se usa en ningún sitio del código: la única forma de
        // llegar a él era este setter, con dos nociones de "pagado" sin nada que las sincronice.
        Fixture f = pendingOrder();

        mockMvc.perform(put("/api/admin/orders/" + f.orderId + "/status")
                        .header("Host", hostHeader(f.store)).header("Authorization", "Bearer " + f.token)
                        .contentType("application/json").content("{\"status\":\"PAID\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/admin/orders/" + f.orderId + "/status")
                        .header("Host", hostHeader(f.store)).header("Authorization", "Bearer " + f.token)
                        .contentType("application/json").content("{\"status\":\"REFUNDED\"}"))
                .andExpect(status().isBadRequest());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Fixture(Store store, String token, long orderId, Product product) {
    }

    private Fixture pendingOrder() throws Exception {
        Store store = createStore("admin-trans");
        disableShipping(store);
        User admin = createAdmin(store, "admin-trans");
        String token = loginAdmin(store, admin);
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));

        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("""
                                {
                                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                                  "region": "RM", "commune": "Santiago",
                                  "items": [{"id":%d,"type":"product","quantity":1}],
                                  "paymentMethod": "manual"
                                }
                                """.formatted(product.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return new Fixture(store, token, objectMapper.readTree(response).get("orderId").asLong(), product);
    }

    private Fixture paidOrder() throws Exception {
        Fixture f = pendingOrder();
        mockMvc.perform(paymentStatus(f, "PAID")).andExpect(status().isOk());
        assertThat(orderRepository.findById(f.orderId).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
        return f;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder paymentStatus(
            Fixture f, String status) {
        return put("/api/admin/orders/" + f.orderId + "/payment-status")
                .header("Host", hostHeader(f.store))
                .header("Authorization", "Bearer " + f.token)
                .contentType("application/json")
                .content("{\"status\":\"" + status + "\"}");
    }

    private int reloadStock(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }
}
