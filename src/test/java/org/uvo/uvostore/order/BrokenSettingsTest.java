package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17. Un ajuste ya guardado se lee igual desde los tres sitios que lo leen.
 *
 * <p>Validar el PUT no cura a las tiendas que ya tienen escrito lo que se tecleó antes, y en la tabla
 * de ajustes puede haber cualquier cosa (una migración, SQL directo, un guardado anterior a este
 * arreglo). Lo que este test fija es que el valor almacenado se interprete <b>de una sola manera</b>:
 * había dos parsers —{@code Double.parseDouble} al cotizar en el checkout y {@code new BigDecimal} en
 * {@code /cart/calculate} y {@code /checkout/config}— que no aceptan el mismo texto, así que el mismo
 * ajuste era válido o inválido según por dónde se entrara.
 */
class BrokenSettingsTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Una tasa con espacios de sobra vale lo mismo en los tres endpoints")
    void aPaddedTaxRateMeansTheSameEverywhere() throws Exception {
        Store store = createStore("padded-tax");
        setSetting(store, "tax_rate", " 19 ");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));

        // Antes: 400 (new BigDecimal(" 19 ") no parsea).
        mockMvc.perform(get("/api/v1/checkout/config").header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxRate").value(19));

        // Antes: 400, por lo mismo.
        mockMvc.perform(calculate(store, product))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxAmount").value(1900))
                .andExpect(jsonPath("$.total").value(11900));

        // Y este cobraba bien desde siempre, con Double.parseDouble, que sí recorta espacios. Ahí
        // estaba la discrepancia: el cliente veía un error al calcular el carrito y el cobro habría
        // sido correcto. Los tres coinciden ahora en el 19 %.
        mockMvc.perform(checkout(store, product))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11900));
    }

    @Test
    @DisplayName("Una tasa que no es un número falla igual en los tres, con un mensaje accionable")
    void aNonNumericTaxRateFailsTheSameWayEverywhere() throws Exception {
        Store store = createStore("broken-tax");
        // Lo que hay en la base de una tienda que guardó basura antes de que el PUT validara.
        setSetting(store, "tax_rate", "abc");
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));

        // Los tres responden 400 —eso ya pasaba— pero antes el cuerpo llevaba el mensaje interno de
        // BigDecimal ("Character a is neither a decimal digit number…") y nadie se enteraba: un 400 no
        // va a Sentry. Ahora el mensaje dice qué ajuste está mal, y SettingValues sí avisa.
        mockMvc.perform(get("/api/v1/checkout/config").header("Host", hostHeader(store)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("tax_rate")));

        mockMvc.perform(calculate(store, product))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("tax_rate")));

        mockMvc.perform(checkout(store, product))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("tax_rate")));
    }

    @Test
    @DisplayName("Un ajuste ausente sigue cayendo a su valor por defecto")
    void aMissingSettingStillFallsBackToItsDefault() throws Exception {
        // La tolerancia que sí hay que conservar: una tienda recién creada no tiene ningún ajuste
        // escrito, y tiene que poder vender con el 19 % por defecto.
        Store store = createStore("no-settings");
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));

        mockMvc.perform(calculate(store, product))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxAmount").value(1900));
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder calculate(
            Store store, Product product) {
        return post("/api/v1/cart/calculate")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("""
                        {"items":[{"id":%d,"type":"product","quantity":1}],"region":"RM","commune":"Santiago"}
                        """.formatted(product.getId()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder checkout(
            Store store, Product product) {
        return post("/api/v1/checkout")
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
                        """.formatted(product.getId()));
    }
}
