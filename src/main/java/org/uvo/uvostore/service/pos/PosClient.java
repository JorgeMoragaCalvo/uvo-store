package org.uvo.uvostore.service.pos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.uvo.uvostore.entity.pos.PosConnection;

import java.util.Map;
import java.util.regex.Pattern;

// Ports PosConnection::apiRequest()/buildEndpointUrl()/notifyOrder() + ProductSyncService::
// notifyOrderToPOS() — the outbound HTTP call to UvoPOS's "orders/external" endpoint.
@Component
public class PosClient {

    private static final Pattern TRAILING_API_VERSION = Pattern.compile("/api/v\\d+$");

    private final RestClient restClient;

    public PosClient(@Value("${app.pos.connect-timeout-ms:5000}") int connectTimeoutMs,
                     @Value("${app.pos.read-timeout-ms:20000}") int readTimeoutMs) {
        this.restClient = RestClient.builder()
                .requestFactory(clientRequestFactory(connectTimeoutMs, readTimeoutMs))
                .build();
    }

    // G3: el cuerpo ya no se arma aquí. Todo lo que depende del contrato con la plataforma SII
    // —nombres de campo, importes, convención de idempotencia— vive en PosOrderPayloadMapper, que
    // al ser una función pura sí se puede fijar con un test; este RestClient se instancia dentro de
    // la clase y no hay dónde interceptarlo.
    @SuppressWarnings("unchecked")
    public Map<String, Object> notifyOrder(PosConnection connection, PosOrderPayload payload) {
        String url = buildEndpointUrl(connection, "orders/external");

        return restClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + connection.getApiKey())
                .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                .header("User-Agent", "UvoStore/1.0")
                // La misma clave que viaja dentro del cuerpo. Se manda por los dos caminos porque no
                // sabemos cuál de las dos convenciones usa la plataforma, y mandar de más es inocuo.
                .header("Idempotency-Key", payload.idempotencyKey())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(payload.body())
                .retrieve()
                .body(Map.class);
    }

    // Ports getBaseUrl() (strip a trailing /api/vN) + buildEndpointUrl()'s "no version prefix"
    // branch (always appends /api/v1/..., this app never calls it with an already-versioned path).
    private String buildEndpointUrl(PosConnection connection, String endpoint) {
        String apiUrl = connection.getApiUrl();
        String baseUrl = TRAILING_API_VERSION.matcher(stripTrailingSlash(apiUrl)).replaceAll("");
        return baseUrl + "/api/v1/" + endpoint;
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // R1: los 120s de lectura que había aquí eran defendibles para un proceso de fondo, pero esto se
    // llama desde el listener del checkout —antes en el propio hilo de la petición— y una por cada
    // empresa a la que haya que notificar. Ahora sale por el posExecutor y con un tope configurable
    // mucho más bajo: si la plataforma SII resulta ser legítimamente más lenta, se sube la propiedad.
    private static org.springframework.http.client.ClientHttpRequestFactory clientRequestFactory(
            int connectTimeoutMs, int readTimeoutMs) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }
}
