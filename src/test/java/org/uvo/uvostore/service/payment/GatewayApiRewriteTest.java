package org.uvo.uvostore.service.payment;

import cl.transbank.util.HttpUtil;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.net.HttpMethod;
import com.mercadopago.net.MPRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * GAP-08. Los dos clientes que desvían Transbank y MercadoPago a los dobles del E2E. Solo cambian el origen:
 * la ruta, la query, las cabeceras y el cuerpo llegan iguales, que es lo que el doble necesita para responder
 * como la pasarela.
 */
class GatewayApiRewriteTest {

    @Test
    @DisplayName("Transbank: el origen cambia y la ruta de la API se conserva")
    void transbankKeepsThePath() throws Exception {
        HttpUtil delegate = mock(HttpUtil.class);
        RewritingHttpUtil util = new RewritingHttpUtil("http://127.0.0.1:18089/webpay/", delegate);
        URL real = URI.create(
                "https://webpay3gint.transbank.cl/rswebpaytransaction/api/webpay/v1.2/transactions/tok").toURL();
        Map<String, String> headers = Map.of("Tbk-Api-Key-Id", "597055555535");

        util.request(real, HttpUtil.RequestMethod.PUT, null, headers, Object.class);

        verify(delegate).request(
                eq(URI.create("http://127.0.0.1:18089/webpay/rswebpaytransaction/api/webpay/v1.2/transactions/tok").toURL()),
                eq(HttpUtil.RequestMethod.PUT), any(), eq(headers), eq(Object.class));
    }

    @Test
    @DisplayName("Transbank: los timeouts siguen llegando al cliente real")
    void transbankForwardsTimeouts() {
        HttpUtil delegate = mock(HttpUtil.class);
        RewritingHttpUtil util = new RewritingHttpUtil("http://127.0.0.1:18089/webpay", delegate);

        util.setConnectTimeout(1234);
        util.setReadTimeout(5678);

        verify(delegate).setConnectTimeout(1234);
        verify(delegate).setReadTimeout(5678);
    }

    @Test
    @DisplayName("MercadoPago: la base del SDK cambia y el resto de la petición queda igual")
    void mercadoPagoKeepsTheRequest() {
        RewritingMpHttpClient client = new RewritingMpHttpClient("http://127.0.0.1:18089/mercadopago");
        MPRequest request = MPRequest.builder()
                .uri(MercadoPagoConfig.BASE_URL + "/v1/payments/search?external_reference=ORD-1")
                .method(HttpMethod.GET)
                .headers(Map.of("Authorization", "Bearer token-de-la-tienda"))
                .accessToken("token-de-la-tienda")
                .socketTimeout(20000)
                .build();

        MPRequest rewritten = client.rewrite(request);

        assertThat(rewritten.getUri())
                .isEqualTo("http://127.0.0.1:18089/mercadopago/v1/payments/search?external_reference=ORD-1");
        assertThat(rewritten.getMethod()).isEqualTo(HttpMethod.GET);
        assertThat(rewritten.getHeaders()).containsEntry("Authorization", "Bearer token-de-la-tienda");
        assertThat(rewritten.getAccessToken()).isEqualTo("token-de-la-tienda");
        assertThat(rewritten.getSocketTimeout()).isEqualTo(20000);
    }
}
