package org.uvo.uvostore.url;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-04. Tras pagar con Webpay, el comprador vuelve al storefront de <b>su</b> tienda.
 *
 * <p>Era el peor de los tres caminos de retorno: Transbank devuelve el navegador a este endpoint del
 * backend por un POST con el formulario, y la redirección de aquí a la SPA salía de {@code app.frontend-url}
 * —una sola para todas las tiendas— <b>sin que el cliente pudiera sobreescribirla</b>. Es decir: el
 * comprador de cualquier tienda acababa en el storefront de otra, con su pedido ya pagado.
 *
 * <p>Los dos caminos que se comprueban aquí no hablan con Transbank: el de cancelación porque no hay token
 * que confirmar, y el de error porque la orden no se encuentra antes de llegar a la pasarela. Lo que hace
 * {@code commit()} con un token real ya lo cubren {@code WebpayReconcileTest} y {@code WebpayRefundTest}.
 */
class WebpayReturnRedirectTest extends IntegrationTestSupport {

    @Test
    @DisplayName("Cancelar en Transbank devuelve al checkout de su tienda, y dos tiendas no comparten host")
    void cancellingReturnsToTheOwnStoreCheckout() throws Exception {
        Store first = createStore("wp-ret-a");
        Store second = createStore("wp-ret-b");

        // Sin token_ws: el comprador abandonó en la página de Transbank (caso TBK_TOKEN).
        mockMvc.perform(post("/api/v1/webpay/return").header("Host", hostHeader(first)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://" + hostHeader(first) + "/checkout?canceled=1"));

        mockMvc.perform(post("/api/v1/webpay/return").header("Host", hostHeader(second)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://" + hostHeader(second) + "/checkout?canceled=1"));
    }

    @Test
    @DisplayName("Un token que no corresponde a ninguna orden también redirige a su propia tienda")
    void anUnknownTokenStillRedirectsToTheOwnStore() throws Exception {
        Store store = createStore("wp-ret-err");

        mockMvc.perform(post("/api/v1/webpay/return")
                        .header("Host", hostHeader(store))
                        .param("token_ws", "token-que-no-existe"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://" + hostHeader(store) + "/checkout?error=webpay"));
    }

    @Test
    @DisplayName("Con dominio propio verificado, la vuelta es a ese dominio")
    void aVerifiedCustomDomainIsUsedOnTheWayBack() throws Exception {
        Store store = createStore("wp-ret-dom");
        String domain = "wp-" + nextSeq() + ".example";
        store.setDomain(domain);
        store.setDomainVerifiedAt(java.time.Instant.now());
        storeRepository.save(store);

        // Se entra por el subdominio —que es como Transbank conoce la URL de retorno si el dominio se
        // verificó después— y aun así la vuelta usa el dominio propio, que es la dirección pública.
        mockMvc.perform(post("/api/v1/webpay/return").header("Host", hostHeader(store)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://" + domain + "/checkout?canceled=1"));
    }
}
