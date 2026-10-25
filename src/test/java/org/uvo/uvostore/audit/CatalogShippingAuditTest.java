package org.uvo.uvostore.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.AuditRows;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PROD-18. Lo que cambia cuánto se cobra: cupones, tarifas y métodos de envío, y precios hechos desde el panel. */
class CatalogShippingAuditTest extends IntegrationTestSupport {

    private Store store;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        store = createStore("audit-catalog");
        User admin = createAdmin(store, "audit-catalog");
        token = loginAdmin(store, admin);
    }

    @Test
    @DisplayName("Un cupón deja su alta, sus cambios (sin el texto de la descripción), el interruptor y su baja")
    void couponLifecycleIsRecorded() throws Exception {
        long couponId = idOf(json(post("/api/admin/coupons"),
                "{\"code\":\"AUD10\",\"name\":\"10 off\",\"type\":\"percentage\",\"value\":10,\"active\":true}"));
        String update = "{\"code\":\"AUD10\",\"name\":\"10 off\",\"description\":\"texto privado del cupon\","
                + "\"type\":\"percentage\",\"value\":15,\"active\":true}";
        json(put("/api/admin/coupons/" + couponId), update);
        json(put("/api/admin/coupons/" + couponId), update);
        mockMvc.perform(auth(post("/api/admin/coupons/" + couponId + "/toggle-status"))).andExpect(status().isOk());
        mockMvc.perform(auth(delete("/api/admin/coupons/" + couponId))).andExpect(status().isNoContent());

        assertThat(auditRow(store, "COUPON_CREATE").json().path("value").asText()).isEqualTo("10");
        AuditRows.Row updated = auditRow(store, "COUPON_UPDATE");
        assertThat(updated.change("value").path("from").asText()).isEqualTo("10");
        assertThat(updated.change("value").path("to").asText()).isEqualTo("15");
        assertThat(updated.json().path("changedFields").toString()).contains("description");
        assertThat(updated.details()).doesNotContain("texto privado");
        assertThat(auditRow(store, "COUPON_STATUS_TOGGLE").change("active").path("to").asBoolean()).isFalse();
        assertThat(auditRow(store, "COUPON_DELETE").json().path("code").asText()).isEqualTo("AUD10");
    }

    @Test
    @DisplayName("Una tarifa y un método de envío dejan su alta, sus cambios reales, el interruptor y su baja")
    void shippingRatesAndMethodsAreRecorded() throws Exception {
        long methodId = idOf(json(post("/api/admin/shipping/methods"),
                "{\"name\":\"Despacho\",\"code\":\"aud-" + nextSeq() + "\",\"type\":\"COURIER\",\"hasApiIntegration\":false,"
                        + "\"active\":true,\"sortOrder\":0}"));
        long zoneId = idOf(json(post("/api/admin/shipping/zones"),
                "{\"name\":\"Zona " + nextSeq() + "\",\"regions\":[\"RM\"],\"communes\":[],\"active\":true,\"sortOrder\":0}"));
        String rate = "{\"methodId\":" + methodId + ",\"zoneId\":" + zoneId
                + ",\"name\":\"Tarifa\",\"rateType\":\"flat\",\"flatRate\":%s,\"active\":true,\"sortOrder\":0}";
        long rateId = idOf(json(post("/api/admin/shipping/rates"), rate.formatted("3000")));
        json(put("/api/admin/shipping/rates/" + rateId), rate.formatted("3000.00"));
        json(put("/api/admin/shipping/rates/" + rateId), rate.formatted("4500"));
        mockMvc.perform(auth(post("/api/admin/shipping/rates/" + rateId + "/toggle-status"))).andExpect(status().isOk());
        mockMvc.perform(auth(delete("/api/admin/shipping/rates/" + rateId))).andExpect(status().isNoContent());
        mockMvc.perform(auth(post("/api/admin/shipping/methods/" + methodId + "/toggle-status"))).andExpect(status().isOk());

        assertThat(auditRow(store, "SHIPPING_RATE_CREATE").json().path("flatRate").asText()).isEqualTo("3000");
        // 3000.00 es el mismo monto: solo el cambio a 4500 deja fila.
        AuditRows.Row updated = auditRow(store, "SHIPPING_RATE_UPDATE");
        assertThat(updated.change("flatRate").path("to").asText()).isEqualTo("4500");
        assertThat(updated.json().path("changes").size()).isEqualTo(1);
        assertThat(auditRow(store, "SHIPPING_RATE_STATUS_TOGGLE").change("active").path("to").asBoolean()).isFalse();
        assertThat(auditRow(store, "SHIPPING_RATE_DELETE").targetId()).isEqualTo(rateId);
        assertThat(auditRow(store, "SHIPPING_METHOD_CREATE").targetId()).isEqualTo(methodId);
        assertThat(auditRow(store, "SHIPPING_METHOD_STATUS_TOGGLE").change("active").path("to").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("El precio de un producto: un cambio deja fila; el mismo precio con otra escala o editar otra cosa, no")
    void productPriceChangesAreRecorded() throws Exception {
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Polera", new BigDecimal("10000.00"));

        mockMvc.perform(productUpdate(product, category, "Polera", "10000")).andExpect(status().isOk());
        mockMvc.perform(productUpdate(product, category, "Polera renombrada", "10000")).andExpect(status().isOk());
        mockMvc.perform(productUpdate(product, category, "Polera renombrada", "12990")).andExpect(status().isOk());

        List<AuditRows.Row> rows = auditRows(store, "PRODUCT_PRICE_UPDATE");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().targetId()).isEqualTo(product.getId());
        assertThat(rows.getFirst().change("price").path("from").asText()).isEqualTo("10000");
        assertThat(rows.getFirst().change("price").path("to").asText()).isEqualTo("12990");
        assertThat(rows.getFirst().json().path("sku").asText()).isEqualTo(product.getSku());
    }

    @Test
    @DisplayName("El precio de una variante: un cambio deja fila, lo demás no")
    void variationPriceChangesAreRecorded() throws Exception {
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Zapatilla", new BigDecimal("20000"));
        String firstSku = "V-AUD-" + nextSeq();
        // La respuesta es el producto, no la variante: el id se busca por el SKU.
        json(post("/api/admin/products/" + product.getId() + "/variations"), variation(firstSku, 20000, 3));
        long variationId = jdbcTemplate.queryForObject("SELECT id FROM product_variations WHERE sku = ?", Long.class,
                firstSku);
        String sku = "V-AUD-" + nextSeq();

        json(put("/api/admin/products/" + product.getId() + "/variations/" + variationId), variation(sku, 20000, 9));
        json(put("/api/admin/products/" + product.getId() + "/variations/" + variationId), variation(sku, 21990, 9));

        AuditRows.Row row = auditRow(store, "VARIATION_PRICE_UPDATE");
        assertThat(row.targetType()).isEqualTo("PRODUCT_VARIATION");
        assertThat(row.targetId()).isEqualTo(variationId);
        assertThat(row.change("price").path("to").asText()).isEqualTo("21990");
        assertThat(row.json().path("productId").asLong()).isEqualTo(product.getId());
    }

    private MockMultipartHttpServletRequestBuilder productUpdate(Product product, Category category, String name,
                                                                 String price) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/admin/products/" + product.getId());
        request.with(req -> {
            req.setMethod("PUT");
            return req;
        });
        auth(request)
                .param("productType", "simple")
                .param("name", name)
                .param("categoryId", String.valueOf(category.getId()))
                .param("active", "true")
                .param("isFeatured", "true")
                .param("isNew", "false")
                .param("sortOrder", "0")
                .param("isOnSale", "false")
                .param("sku", product.getSku())
                .param("price", price)
                .param("stock", "10")
                .param("manageStock", "true");
        return request;
    }

    private static String variation(String sku, int price, int stock) {
        return "{\"attributeValueIdsByAttributeId\":{},\"price\":" + price + ",\"stock\":" + stock + ",\"sku\":\"" + sku + "\"}";
    }

    private String json(AbstractMockHttpServletRequestBuilder<?> request, String body) throws Exception {
        return mockMvc.perform(auth(request).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long idOf(String response) throws Exception {
        return objectMapper.readTree(response).get("id").asLong();
    }

    private <T extends AbstractMockHttpServletRequestBuilder<?>> T auth(T request) {
        request.header("Host", hostHeader(store)).header("Authorization", "Bearer " + token);
        return request;
    }
}
