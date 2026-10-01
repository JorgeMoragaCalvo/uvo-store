package org.uvo.uvostore.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.uvo.uvostore.entity.settings.Setting;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17. Los ajustes que participan en dinero no se guardan sin comprobar.
 *
 * <p>Los cuatro campos del formulario del panel son {@code input} de texto sin tipo, así que un
 * {@code tax_rate = abc} entraba tal cual y dejaba cotizar y comprar en error. Peor que el texto no
 * numérico eran los números válidos: una tasa negativa cobra por debajo del precio del producto sin
 * lanzar nada, y {@code -100} con precios con IVA incluido divide por cero.
 */
class SettingsValidationTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Una tasa de impuesto que no es un número se rechaza y no se escribe")
    void aNonNumericTaxRateIsRejected() throws Exception {
        Admin admin = admin("tax-abc");

        save(admin, body().taxRate("abc")).andExpect(status().isBadRequest());

        // Lo que importa no es solo el 400: es que el ajuste no quedó escrito. La escritura ocurría
        // antes de que nadie mirara el valor.
        assertThat(stored(admin.store(), "tax_rate")).isEmpty();
    }

    @Test
    @DisplayName("Una tasa negativa se rechaza: cobraría por debajo del precio del producto")
    void aNegativeTaxRateIsRejected() throws Exception {
        Admin admin = admin("tax-negative");

        // taxAmount = subtotal × -0,19, negativo, y total = subtotal + taxAmount. Ninguna excepción,
        // ningún log: la orden simplemente se cobra más barata que el producto.
        save(admin, body().taxRate("-19")).andExpect(status().isBadRequest());
        // Y este además tira el checkout con ArithmeticException: divide por (1 + (-1)).
        save(admin, body().taxRate("-100")).andExpect(status().isBadRequest());

        assertThat(stored(admin.store(), "tax_rate")).isEmpty();
    }

    @Test
    @DisplayName("Una tasa por encima de 100 se rechaza")
    void aTaxRateAboveOneHundredIsRejected() throws Exception {
        Admin admin = admin("tax-huge");

        // El dedo que resbaló en el símbolo de porcentaje: multiplica el total por 20.
        save(admin, body().taxRate("1900")).andExpect(status().isBadRequest());
        save(admin, body().taxRate("100.01")).andExpect(status().isBadRequest());
        save(admin, body().taxRate("100")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Una tasa de impuesto vacía se rechaza")
    void aBlankTaxRateIsRejected() throws Exception {
        Admin admin = admin("tax-blank");

        // Guardaba null en settings.value, y Double.parseDouble(null) lanzaba NullPointerException:
        // el único de todos estos casos que llegaba a ser un 500.
        save(admin, body().taxRate("")).andExpect(status().isBadRequest());

        assertThat(stored(admin.store(), "tax_rate")).isEmpty();
    }

    @Test
    @DisplayName("Los importes en pesos no admiten negativos ni centavos")
    void amountsRejectNegativesAndCents() throws Exception {
        Admin admin = admin("amounts");

        save(admin, body().defaultShippingCost("-1000")).andExpect(status().isBadRequest());
        // El CLP no tiene centavos (Money): un umbral con decimales no se puede comparar con un total
        // que siempre es entero.
        save(admin, body().freeShippingThreshold("1500.50")).andExpect(status().isBadRequest());
        save(admin, body().freeShippingThreshold("abc")).andExpect(status().isBadRequest());

        save(admin, body().defaultShippingCost("3990").freeShippingThreshold("30000"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("La divisa sale de un catálogo, y se guarda normalizada")
    void theCurrencyComesFromACatalogue() throws Exception {
        Admin admin = admin("currency");

        // usd con un total en pesos cobraría 118,88 dólares por un pedido de unos 12 — y la
        // comprobación de importe de markPaid no lo vería, porque compara números sin unidad.
        save(admin, body().currency("usd")).andExpect(status().isBadRequest());
        save(admin, body().currency("")).andExpect(status().isBadRequest());

        save(admin, body().currency("clp")).andExpect(status().isOk());
        assertThat(stored(admin.store(), "currency")).contains("CLP");
    }

    @Test
    @DisplayName("Un valor con espacios de sobra se guarda recortado")
    void surroundingWhitespaceIsTrimmedOnSave() throws Exception {
        Admin admin = admin("trim");

        // Este era el caso feo: Double.parseDouble(" 19 ") vale 19 y new BigDecimal(" 19 ") no, así
        // que el checkout cobraba bien mientras /cart/calculate y /checkout/config respondían 400.
        save(admin, body().taxRate(" 19 ")).andExpect(status().isOk());

        assertThat(stored(admin.store(), "tax_rate")).contains("19");
    }

    @Test
    @DisplayName("Activar Stripe sin claves se rechaza, pero no si ya estaban guardadas")
    void enablingStripeRequiresCredentials() throws Exception {
        Admin admin = admin("stripe");

        // Sin esto, la SPA muestra el botón de pago y responde 500 al pulsarlo: Stripe rechaza la
        // llamada sin clave y la excepción se envuelve en IllegalStateException.
        save(admin, body().stripeEnabled(true)).andExpect(status().isBadRequest());
        save(admin, body().stripeEnabled(true).stripePublicKey("pk_test_1"))
                .andExpect(status().isBadRequest());

        save(admin, body().stripeEnabled(true).stripePublicKey("pk_test_1").stripeSecretKey("sk_test_1"))
                .andExpect(status().isOk());

        // Y un guardado posterior que no toca el secreto —el panel nunca puede pre-rellenarlo— no
        // puede fallar por eso: es la regla de setSecret.
        save(admin, body().stripeEnabled(true).stripePublicKey("pk_test_1"))
                .andExpect(status().isOk());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Admin(Store store, String token) {
    }

    private Admin admin(String prefix) throws Exception {
        Store store = createStore("settings-" + prefix);
        User user = createAdmin(store, "settings-" + prefix);
        return new Admin(store, loginAdmin(store, user));
    }

    private Optional<String> stored(Store store, String key) {
        return settingRepository.findByStoreIdAndSettingKey(store.getId(), key).map(Setting::getValue);
    }

    private ResultActions save(Admin admin, Body body) throws Exception {
        return mockMvc.perform(put("/api/admin/settings/general")
                .header("Host", hostHeader(admin.store()))
                .header("Authorization", "Bearer " + admin.token())
                .contentType("application/json")
                .content(body.json()));
    }

    private Body body() {
        return new Body();
    }

    /** Cuerpo válido por defecto, con solo el campo en prueba cambiado. */
    private static final class Body {
        private String currency = "CLP";
        private String taxRate = "19";
        private String defaultShippingCost = "0";
        private String freeShippingThreshold = "0";
        private String stripePublicKey = "";
        private String stripeSecretKey = "";
        private boolean stripeEnabled = false;

        Body currency(String value) {
            this.currency = value;
            return this;
        }

        Body taxRate(String value) {
            this.taxRate = value;
            return this;
        }

        Body defaultShippingCost(String value) {
            this.defaultShippingCost = value;
            return this;
        }

        Body freeShippingThreshold(String value) {
            this.freeShippingThreshold = value;
            return this;
        }

        Body stripePublicKey(String value) {
            this.stripePublicKey = value;
            return this;
        }

        Body stripeSecretKey(String value) {
            this.stripeSecretKey = value;
            return this;
        }

        Body stripeEnabled(boolean value) {
            this.stripeEnabled = value;
            return this;
        }

        String json() {
            return """
                    {
                      "storeName":"Tienda","storeEmail":"a@test.local","storePhone":"","adminEmail":"admin@test.local",
                      "currency":"%s","currencySymbol":"$","taxRate":"%s","pricesIncludeTax":false,
                      "shippingEnabled":true,"defaultShippingCost":"%s","freeShippingEnabled":false,
                      "freeShippingThreshold":"%s",
                      "allowGuestCheckout":true,"requirePhone":false,"requireCompany":false,
                      "stripePublicKey":"%s","stripeSecretKey":"%s","stripeEnabled":%s,
                      "posApiUrl":"","posApiToken":"","posWebhookSecret":"","posSyncEnabled":false,
                      "metaTitle":"","metaDescription":"","metaKeywords":"",
                      "facebookUrl":"","instagramUrl":"","twitterUrl":""
                    }
                    """.formatted(currency, taxRate, defaultShippingCost, freeShippingThreshold,
                    stripePublicKey, stripeSecretKey, stripeEnabled);
        }
    }
}
