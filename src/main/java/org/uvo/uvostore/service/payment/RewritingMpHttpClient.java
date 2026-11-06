package org.uvo.uvostore.service.payment;

import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.MPDefaultHttpClient;
import com.mercadopago.net.MPRequest;
import com.mercadopago.net.MPResponse;
import org.apache.http.impl.client.HttpClients;

/**
 * GAP-08. El cliente HTTP del SDK de MercadoPago con otro origen: cambia {@link MercadoPagoConfig#BASE_URL} por
 * {@code apiBase} en cada petición. {@code BASE_URL} es una constante y el cliente por defecto solo registra https,
 * así que no basta con un proxy: se construye sobre un {@code HttpClient} de Apache que también acepta http.
 *
 * <p>Solo lo instala {@code GatewayApiBaseConfig}, con {@code MERCADOPAGO_API_BASE} puesta, para el E2E.
 */
public class RewritingMpHttpClient extends MPDefaultHttpClient {

    private final String apiBase;

    public RewritingMpHttpClient(String apiBase) {
        super(HttpClients.createDefault());
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
    }

    @Override
    public MPResponse send(MPRequest request) throws MPException, MPApiException {
        return super.send(rewrite(request));
    }

    MPRequest rewrite(MPRequest request) {
        String uri = request.getUri();
        if (uri == null || !uri.startsWith(MercadoPagoConfig.BASE_URL)) {
            return request;
        }
        return MPRequest.builder()
                .uri(apiBase + uri.substring(MercadoPagoConfig.BASE_URL.length()))
                .method(request.getMethod())
                .headers(request.getHeaders())
                .payload(request.getPayload())
                .queryParams(request.getQueryParams())
                .accessToken(request.getAccessToken())
                .connectionTimeout(request.getConnectionTimeout())
                .connectionRequestTimeout(request.getConnectionRequestTimeout())
                .socketTimeout(request.getSocketTimeout())
                .build();
    }
}
