package org.uvo.uvostore.pos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;
import org.uvo.uvostore.support.WithPosIntegration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-12. Conectar el POS exige el secreto con el que se firman sus webhooks.
 *
 * <p>Antes la conexión quedaba {@code active = true} de forma incondicional, con el secreto en cadena
 * vacía si el administrador dejaba el campo en blanco — ni ping, ni handshake, ni validación de ninguna
 * clase. Y una conexión activa con el secreto vacío no queda abierta, queda <b>rota</b>: lo demuestra el
 * último caso de esta clase.
 *
 * <p>Con {@code @WithPosIntegration} porque configurar el POS con la integración apagada responde 404 —
 * eso es {@code PosSurfaceDisabledTest}; lo que se mide aquí es la validación de la activación.
 */
@WithPosIntegration
class PosConnectionActivationTest extends IntegrationTestSupport {

    @Autowired
    private PosConnectionRepository posConnectionRepository;

    @Test
    @DisplayName("Sin secreto de webhook la conexión se rechaza y no queda creada")
    void aMissingWebhookSecretIsRejected() throws Exception {
        Fixture fixture = fixture("pos-nosecret");

        mockMvc.perform(settingsPut(fixture, "uvp_" + fixture.store().getId() + "_abc123", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("secreto de webhook")));

        assertThat(posConnectionRepository.findByStoreId(fixture.store().getId())).isEmpty();
    }

    @Test
    @DisplayName("Con secreto la conexión se crea y queda activa")
    void aWebhookSecretActivatesTheConnection() throws Exception {
        Fixture fixture = fixture("pos-secret");

        mockMvc.perform(settingsPut(fixture, "uvp_" + fixture.store().getId() + "_abc123", "whsec_de_prueba_12345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posApiTokenConfigured").value(true))
                .andExpect(jsonPath("$.posWebhookSecretConfigured").value(true));

        PosConnection connection = posConnectionRepository.findByStoreId(fixture.store().getId()).orElseThrow();
        assertThat(connection.isActive()).isTrue();
        assertThat(connection.getWebhookSecret()).isEqualTo("whsec_de_prueba_12345");
    }

    @Test
    @DisplayName("Una conexión activa con el secreto vacío no autentica nada: está rota, no abierta")
    void anEmptySecretBreaksEveryIncomingWebhook() throws Exception {
        // Las filas que ya existan así siguen en la base, y esto deja escrito qué les pasa de verdad en
        // vez de suponerlo: PosWebhookAuthFilter firma sin comprobar que el secreto exista, y un HMAC con
        // clave vacía no se puede construir. No es que cualquiera pueda falsificar la firma — es que
        // ninguna, ni la auténtica, llega a verificarse.
        Store store = createStore("pos-empty-secret");
        PosConnection connection = new PosConnection();
        connection.setStore(store);
        connection.setCompanyId(9191L);
        connection.setCompanyName("UvoPOS");
        connection.setApiKey("uvp_9191_clave");
        connection.setWebhookSecret("");
        connection.setApiUrl("https://pos.invalid");
        connection.setActive(true);
        posConnectionRepository.save(connection);

        String body = "{\"company_id\":9191,\"product_id\":1,\"old_stock\":5,\"new_stock\":4}";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

        // Y esto es lo que pasa de verdad, comprobado y no supuesto: la petición ni se rechaza ni se
        // acepta — revienta al construir el HMAC. MockMvc relanza la excepción del filtro; en un
        // contenedor de verdad sale por su página de error, un 500 sin cuerpo del proyecto, porque
        // GlobalExceptionHandler solo atiende lo que llega a un controlador.
        assertThatThrownBy(() -> mockMvc.perform(post("/api/webhooks/pos/stock-updated")
                .header("Host", hostHeader(store))
                .header("X-Company-ID", "9191")
                .header("X-Timestamp", timestamp)
                .header("X-Signature", hmacWithEmptyKeyIsImpossible())
                .contentType("application/json")
                .content(body)))
                .rootCause()
                .hasMessageContaining("Empty key");
    }

    /**
     * No hay forma de calcular la firma que esa conexión esperaría, porque {@code SecretKeySpec} rechaza
     * una clave vacía — que es justamente el problema. Se manda una cualquiera.
     */
    private static String hmacWithEmptyKeyIsImpossible() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("x".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal("lo que sea".getBytes(StandardCharsets.UTF_8)));
    }

    private record Fixture(Store store, String token) {
    }

    private Fixture fixture(String prefix) throws Exception {
        Store store = createStore(prefix);
        User admin = createAdmin(store, prefix);
        return new Fixture(store, loginAdmin(store, admin));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder settingsPut(
            Fixture fixture, String posToken, String webhookSecret) {
        return put("/api/admin/settings/general")
                .header("Host", hostHeader(fixture.store()))
                .header("Authorization", "Bearer " + fixture.token())
                .contentType("application/json")
                .content("""
                        {
                          "storeName":"Tienda","storeEmail":"tienda@test.local","storePhone":"","adminEmail":"admin@test.local",
                          "currency":"CLP","currencySymbol":"$","taxRate":"19","pricesIncludeTax":true,
                          "shippingEnabled":true,"defaultShippingCost":"0","freeShippingEnabled":false,"freeShippingThreshold":"0",
                          "allowGuestCheckout":true,"requirePhone":false,"requireCompany":false,
                          "stripePublicKey":"","stripeSecretKey":"","stripeEnabled":false,
                          "posApiUrl":"https://pos.test","posApiToken":"%s","posWebhookSecret":"%s","posSyncEnabled":true,
                          "metaTitle":"","metaDescription":"","metaKeywords":"","facebookUrl":"","instagramUrl":"","twitterUrl":""
                        }
                        """.formatted(posToken, webhookSecret));
    }
}
