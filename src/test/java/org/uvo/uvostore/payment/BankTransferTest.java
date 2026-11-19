package org.uvo.uvostore.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.service.outbox.EmailDraft;
import org.uvo.uvostore.service.outbox.OrderEmailComposer;
import org.uvo.uvostore.service.payment.transfer.BankTransferService;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IMP-07. El pago manual es una transferencia bancaria que la tienda configura (V37): sin datos no se activa, apagada
 * no se ofrece ni se acepta, el comprador ve cómo pagar y hasta cuándo, y la orden vence en ese mismo plazo.
 *
 * <p>Que el job de abandonadas cancele lo que esta consulta elige lo prueba {@code AbandonedOrderJobTest}; aquí no se
 * corre el job entero porque recorrería todas las tiendas de la base. La devolución de un pago manual (registro
 * externo) la cubre {@code RefundServiceTest}.
 */
class BankTransferTest extends IntegrationTestSupport {

    private static final String COMPLETE = """
            {"enabled":true,"holderName":"Comercial Ejemplo SpA","holderRut":"76.123.456-0","bankName":"Banco Estado",
             "accountType":"VISTA","accountNumber":"12345678","noticeEmail":"pagos@ejemplo.cl",
             "instructions":"Indica el número de pedido en el comentario.","paymentWindowHours":24}
            """;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderEmailComposer emailComposer;

    private Store store;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        store = createStore("transfer");
        disableShipping(store);
        token = loginAdmin(store, createAdmin(store, "transfer"));
    }

    @Test
    @DisplayName("No se activa sin los datos que el comprador necesita, ni con un RUT inválido")
    void itCannotBeEnabledHalfConfigured() throws Exception {
        disableBankTransfer(store);

        mockMvc.perform(admin(put("/api/admin/payment-methods/bank-transfer"))
                        .contentType("application/json")
                        .content("{\"enabled\":true,\"holderName\":\"Comercial Ejemplo SpA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("número de cuenta")));

        mockMvc.perform(admin(put("/api/admin/payment-methods/bank-transfer"))
                        .contentType("application/json")
                        .content(COMPLETE.replace("76.123.456-0", "76.123.456-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("dígito verificador")));

        mockMvc.perform(admin(get("/api/admin/payment-methods/bank-transfer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("Con los datos completos se activa, el RUT queda normalizado y el cambio queda auditado")
    void itIsEnabledAndAudited() throws Exception {
        mockMvc.perform(admin(put("/api/admin/payment-methods/bank-transfer"))
                        .contentType("application/json")
                        .content(COMPLETE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.holderRut").value("76123456-0"))
                .andExpect(jsonPath("$.accountTypeLabel").value("Cuenta vista"))
                .andExpect(jsonPath("$.paymentWindowHours").value(24));

        assertThat(auditRows(store, "BANK_TRANSFER_UPDATE")).singleElement().satisfies(row -> {
            assertThat(row.targetType()).isEqualTo("STORE");
            assertThat(row.targetId()).isEqualTo(store.getId());
            assertThat(row.details()).contains("accountNumber").contains("12345678").contains("instructions");
        });
    }

    @Test
    @DisplayName("Un plazo fuera de 1 a 168 horas se rechaza")
    void theWindowIsBounded() throws Exception {
        mockMvc.perform(admin(put("/api/admin/payment-methods/bank-transfer"))
                        .contentType("application/json")
                        .content(COMPLETE.replace("\"paymentWindowHours\":24", "\"paymentWindowHours\":200")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Apagada, el checkout no la ofrece y una orden manual se rechaza")
    void whenOffItIsNotOfferedNorAccepted() throws Exception {
        disableBankTransfer(store);
        Product product = product();
        long before = orderRepository.count();

        mockMvc.perform(get("/api/v1/checkout/config").header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankTransfer").doesNotExist());
        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(checkoutBody(product)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BankTransferService.UNAVAILABLE_REASON));

        assertThat(orderRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("Activa, el checkout muestra los datos y el plazo")
    void whenOnTheCheckoutShowsHowToPay() throws Exception {
        configure();

        mockMvc.perform(get("/api/v1/checkout/config").header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankTransfer.bankName").value("Banco Estado"))
                .andExpect(jsonPath("$.bankTransfer.accountNumber").value("12345678"))
                .andExpect(jsonPath("$.bankTransfer.paymentWindowHours").value(24));
    }

    @Test
    @DisplayName("El correo de pedido recibido dice a dónde transferir, cuánto, con qué referencia y hasta cuándo")
    void theEmailCarriesTheInstructions() throws Exception {
        configure();
        long orderId = manualOrder();

        EmailDraft draft = emailComposer.orderPlaced(orderId);

        var order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethodType.MANUAL);
        assertThat(draft.body())
                .contains("Comercial Ejemplo SpA")
                .contains("76123456-0")
                .contains("Banco Estado")
                .contains("Cuenta vista: 12345678")
                .contains("Referencia: " + order.getOrderNumber())
                .contains("pagos@ejemplo.cl")
                .contains("Indica el número de pedido")
                .contains("Tienes hasta el")
                .contains("$" + order.getTotal());
    }

    @Test
    @DisplayName("La orden vence en el plazo de su tienda, no a las 24 horas de las pasarelas")
    void theOrderExpiresWithTheStoreWindow() throws Exception {
        configure(); // 24 horas en esta tienda
        long expired = manualOrder();
        long stillOnTime = manualOrder();
        backdate(expired, 25);
        backdate(stillOnTime, 23);

        Store patient = createStore("transfer-48"); // la de prueba trae 48 horas
        disableShipping(patient);
        long patientOrder = manualOrder(patient);
        backdate(patientOrder, 30);

        List<Long> due = orderRepository.findExpiredBankTransferIds(Instant.now(), 10_000);

        assertThat(due).contains(expired).doesNotContain(stillOnTime, patientOrder);
        // Y no la cuenta como abandonada de pasarela: esa lista es para las demás.
        assertThat(orderRepository.findAbandonedPending(Instant.now().minusSeconds(3600 * 24),
                org.springframework.data.domain.Pageable.unpaged()))
                .extracting(o -> o.getId()).doesNotContain(expired, patientOrder);
    }

    private void configure() throws Exception {
        mockMvc.perform(admin(put("/api/admin/payment-methods/bank-transfer"))
                        .contentType("application/json")
                        .content(COMPLETE))
                .andExpect(status().isOk());
    }

    private long manualOrder() throws Exception {
        return manualOrder(store);
    }

    private long manualOrder(Store target) throws Exception {
        Category category = createCategory(target, "Cat");
        Product product = createProduct(target, category, "Producto", BigDecimal.valueOf(1000));
        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(target))
                        .contentType("application/json")
                        .content(checkoutBody(product)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("orderId").asLong();
    }

    private void backdate(long orderId, int hours) {
        jdbcTemplate.update("UPDATE orders SET created_at = now() - make_interval(hours => ?) WHERE id = ?",
                hours, orderId);
    }

    private Product product() {
        Category category = createCategory(store, "Cat");
        return createProduct(store, category, "Producto", BigDecimal.valueOf(1000));
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("Host", hostHeader(store)).header("Authorization", "Bearer " + token);
    }

    private static String checkoutBody(Product product) {
        return """
                {
                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [{"id":%d,"type":"product","quantity":1}],
                  "paymentMethod": "manual"
                }
                """.formatted(product.getId());
    }
}
