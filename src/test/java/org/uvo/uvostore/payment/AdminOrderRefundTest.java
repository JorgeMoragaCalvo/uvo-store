package org.uvo.uvostore.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G4, la superficie del panel. El atajo que esto cierra era real: {@code PUT /status} y
 * {@code PUT /payment-status} ponían una orden en REFUNDED sin llamar a ninguna pasarela, así que dos
 * clics dejaban la base diciendo "devuelto" y el dinero cobrado.
 */
class AdminOrderRefundTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("El estado REFUNDED ya no se puede fijar a mano por ninguno de los dos endpoints")
    void refundedCannotBeSetByHand() throws Exception {
        Store store = createStore("refund-guard");
        User admin = createAdmin(store, "refund-guard");
        String token = loginAdmin(store, admin);
        Order order = paidOrder(store);

        mockMvc.perform(put("/api/admin/orders/" + order.getId() + "/status")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"status\":\"REFUNDED\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/admin/orders/" + order.getId() + "/payment-status")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"status\":\"REFUNDED\"}"))
                .andExpect(status().isBadRequest());

        // Y los demás estados siguen funcionando: lo que se cerró es un camino, no el endpoint.
        mockMvc.perform(put("/api/admin/orders/" + order.getId() + "/status")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Reembolsar exige el permiso propio, no basta con gestionar órdenes")
    void refundingNeedsItsOwnPermission() throws Exception {
        Store store = createStore("refund-perm");
        User manager = createAdminWithPermissions(store, "refund-perm", "orders.view", "orders.manage");
        String token = loginAdmin(store, manager);
        Order order = paidOrder(store);

        mockMvc.perform(get("/api/admin/orders/" + order.getId())
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/refund")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Un reembolso externo necesita motivo, y queda registrado en la orden")
    void anExternalRefundNeedsAReasonAndShowsUpOnTheOrder() throws Exception {
        Store store = createStore("refund-ext");
        User admin = createAdmin(store, "refund-ext");
        String token = loginAdmin(store, admin);
        Order order = paidOrder(store);

        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/refund/external")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"amount\":1000}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/refund/external")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"amount\":1000,\"reason\":\"Devuelto por transferencia\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundedAmount").value(1000))
                // Parcial: la orden sigue pagada.
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.refunds[0].type").value("EXTERNAL"))
                .andExpect(jsonPath("$.refunds[0].reason").value("Devuelto por transferencia"));

        // Y el resto, hasta completar el total, cierra la orden.
        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/refund/external")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"reason\":\"Se devolvió el resto\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(10000));
    }

    @Test
    @DisplayName("No se puede devolver más de lo cobrado")
    void refundingMoreThanTheTotalIsRefused() throws Exception {
        Store store = createStore("refund-max");
        User admin = createAdmin(store, "refund-max");
        String token = loginAdmin(store, admin);
        Order order = paidOrder(store);

        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/refund/external")
                        .header("Host", hostHeader(store))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"amount\":10001,\"reason\":\"De más\"}"))
                .andExpect(status().isBadRequest());
    }

    private Order paidOrder(Store store) {
        Category category = createCategory(store, "Reembolsos");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(10000));

        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-REF-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(BigDecimal.valueOf(10000));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(BigDecimal.valueOf(10000));
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentMethod(PaymentMethodType.MANUAL);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setProductName(product.getName());
        item.setProductSku(product.getSku());
        item.setQuantity(1);
        item.setPrice(product.getPrice());
        item.setSubtotal(product.getPrice());
        item.setTaxAmount(BigDecimal.ZERO);
        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        order.setItems(items);

        return orderRepository.saveAndFlush(order);
    }
}
