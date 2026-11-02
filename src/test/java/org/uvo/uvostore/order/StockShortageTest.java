package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.service.order.OrderInventoryService;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GAP-03. Una orden cobrada a la que le faltó stock al confirmar el pago (dos pagos por la última unidad) quedaba con
 * una nota en un historial que el panel no muestra, y se podía despachar como cualquier otra. Ahora queda marcada, no
 * se despacha, aparece en el panel y sale por reponer, por liberar con nota o por el reembolso de siempre.
 *
 * <p>El descuento se llama directamente: cuelga de un listener AFTER_COMMIT que bajo la transacción con rollback de
 * {@code IntegrationTestSupport} no corre (ver {@code AdminOrderTransitionsTest}). La carrera real entre dos pagos
 * está en {@code StockConcurrencyTest}.
 */
class StockShortageTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderInventoryService orderInventoryService;

    @Test
    @DisplayName("Cobrada sin stock: queda marcada, no se despacha por ninguna vía y el panel la muestra")
    void aPaidOrderWithoutStockIsMarkedAndCannotBeDispatched() throws Exception {
        Fixture f = shortOrder();

        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/mark-shipped"), f))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/mark-delivered"), f))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/tracking"), f)
                        .contentType("application/json").content("{\"trackingNumber\":\"TRACK-1\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin(put("/api/admin/orders/" + f.orderId + "/status"), f)
                        .contentType("application/json").content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isBadRequest());
        // Preparar no es despachar.
        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/mark-processing"), f))
                .andExpect(status().isOk());

        mockMvc.perform(admin(get("/api/admin/orders/" + f.orderId), f))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockShortage").value(true))
                .andExpect(jsonPath("$.items[?(@.productId == %d)].stockMissing", f.missing.getId()).value(contains(true)))
                // La otra línea sí se descontó: no se señala.
                .andExpect(jsonPath("$.items[?(@.productId == %d)].stockMissing", f.available.getId()).value(contains(false)));

        mockMvc.perform(admin(get("/api/admin/orders").param("tab", "stock"), f))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains((int) f.orderId)))
                .andExpect(jsonPath("$.content[0].stockShortage").value(true));
        mockMvc.perform(admin(get("/api/admin/orders/stats"), f))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockShortage").value(1));
    }

    @Test
    @DisplayName("Reintentar sin reposición la deja marcada; con reposición descuenta lo que faltaba y la libera")
    void retryingTakesTheMissingStockOnceItArrives() throws Exception {
        Fixture f = shortOrder();

        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/stock-shortage/retry"), f))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockShortage").value(true));

        setStock(f.missing, 5);
        int availableBefore = stock(f.available);

        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/stock-shortage/retry"), f))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockShortage").value(false))
                .andExpect(jsonPath("$.items[*].stockMissing").value(contains(false, false)));

        assertThat(stock(f.missing)).as("se descuenta la línea que faltaba").isEqualTo(4);
        assertThat(stock(f.available)).as("y no otra vez la que ya se había descontado").isEqualTo(availableBefore);
        assertThat(orderRepository.findById(f.orderId).orElseThrow().getItems())
                .as("las dos líneas quedan con su marca, así que una cancelación las devuelve")
                .allMatch(item -> item.isStockApplied());

        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/mark-shipped"), f))
                .andExpect(status().isOk());
        // Ya no está marcada: un segundo reintento no tiene nada que hacer.
        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/stock-shortage/retry"), f))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Liberar el bloqueo exige una nota, queda auditado sin ella y no inventa stock")
    void releasingNeedsANoteAndIsAudited() throws Exception {
        Fixture f = shortOrder();

        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/stock-shortage/release"), f)
                        .contentType("application/json").content("{\"note\":\"  \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/stock-shortage/release"), f)
                        .contentType("application/json").content("{\"note\":\"Conseguido en la otra sucursal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockShortage").value(false));

        AuditRows.Row row = auditRow(f.store, "ORDER_STOCK_SHORTAGE_RELEASE");
        assertThat(row.targetId()).isEqualTo(f.orderId);
        assertThat(row.details()).contains("\"missingLines\"").contains("1");
        assertThat(row.details()).as("la nota es texto libre y va al historial, no a la auditoría")
                .doesNotContain("sucursal");

        // La línea que faltaba sigue sin marca: si después se cancela, no devuelve una unidad que nunca salió.
        assertThat(orderRepository.findById(f.orderId).orElseThrow().getItems())
                .filteredOn(item -> item.getProduct().getId().equals(f.missing.getId()))
                .allMatch(item -> !item.isStockApplied());

        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/mark-shipped"), f))
                .andExpect(status().isOk());
        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/stock-shortage/release"), f)
                        .contentType("application/json").content("{\"note\":\"Otra vez\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Con stock para todo no se marca nada")
    void anOrderWithStockIsNotMarked() throws Exception {
        Fixture f = paidOrder();
        orderInventoryService.applyOrderStock(orderRepository.findById(f.orderId).orElseThrow());

        assertThat(orderRepository.findById(f.orderId).orElseThrow().isStockShortage()).isFalse();
        mockMvc.perform(admin(post("/api/admin/orders/" + f.orderId + "/mark-shipped"), f))
                .andExpect(status().isOk());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Fixture(Store store, String token, long orderId, Product missing, Product available) {
    }

    /** Pagada con dos líneas; la de {@code missing} se queda sin stock antes del descuento. */
    private Fixture shortOrder() throws Exception {
        Fixture f = paidOrder();
        setStock(f.missing, 0);
        Order order = orderRepository.findById(f.orderId).orElseThrow();
        orderInventoryService.applyOrderStock(order);
        assertThat(orderRepository.findById(f.orderId).orElseThrow().isStockShortage()).isTrue();
        return f;
    }

    private Fixture paidOrder() throws Exception {
        Store store = createStore("shortage");
        disableShipping(store);
        User admin = createAdmin(store, "shortage");
        String token = loginAdmin(store, admin);
        Category category = createCategory(store, "Cat");
        Product missing = createProduct(store, category, "Última unidad", BigDecimal.valueOf(1000));
        Product available = createProduct(store, category, "Con stock", BigDecimal.valueOf(2000));

        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content("""
                                {
                                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                                  "region": "RM", "commune": "Santiago",
                                  "items": [{"id":%d,"type":"product","quantity":1},{"id":%d,"type":"product","quantity":1}],
                                  "paymentMethod": "manual"
                                }
                                """.formatted(missing.getId(), available.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(response).get("orderId").asLong();

        Fixture f = new Fixture(store, token, orderId, missing, available);
        mockMvc.perform(admin(put("/api/admin/orders/" + orderId + "/payment-status"), f)
                        .contentType("application/json").content("{\"status\":\"PAID\"}"))
                .andExpect(status().isOk());
        assertThat(orderRepository.findById(orderId).orElseThrow().getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        return f;
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request, Fixture f) {
        return request.header("Host", hostHeader(f.store)).header("Authorization", "Bearer " + f.token);
    }

    private void setStock(Product product, int stock) {
        Product current = productRepository.findById(product.getId()).orElseThrow();
        current.setStock(stock);
        productRepository.saveAndFlush(current);
    }

    private int stock(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }
}
