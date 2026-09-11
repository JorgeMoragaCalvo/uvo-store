package org.uvo.uvostore.service.pos;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.uvo.uvostore.entity.pos.PosConnection;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

// Ports PosConnection::apiRequest()/buildEndpointUrl()/notifyOrder() + ProductSyncService::
// notifyOrderToPOS() — the outbound HTTP call to UvoPOS's "orders/external" endpoint.
@Component
public class PosClient {

    private static final Pattern TRAILING_API_VERSION = Pattern.compile("/api/v\\d+$");

    private final RestClient restClient = RestClient.builder()
            .requestFactory(clientRequestFactory())
            .build();

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

    private static org.springframework.http.client.ClientHttpRequestFactory clientRequestFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
        return factory;
    }
}
