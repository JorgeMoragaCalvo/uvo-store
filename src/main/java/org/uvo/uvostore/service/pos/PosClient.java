package org.uvo.uvostore.service.pos;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.uvo.uvostore.entity.pos.PosConnection;

import java.time.Duration;
import java.util.List;
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

    @SuppressWarnings("unchecked")
    public Map<String, Object> notifyOrder(PosConnection connection, String orderNumber, List<PosOrderItem> items) {
        String url = buildEndpointUrl(connection, "orders/external");

        List<Map<String, Object>> itemPayload = items.stream()
                .map(i -> Map.<String, Object>of("product_id", i.productId(), "quantity", i.quantity(), "price", i.price()))
                .toList();

        Map<String, Object> body = Map.of(
                "source", "uvostore",
                "order_number", orderNumber,
                "items", itemPayload
        );

        return restClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + connection.getApiKey())
                .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                .header("User-Agent", "UvoStore/1.0")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
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
