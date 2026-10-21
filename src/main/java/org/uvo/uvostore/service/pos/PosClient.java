package org.uvo.uvostore.service.pos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.uvo.uvostore.entity.pos.PosConnection;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// Ports PosConnection::apiRequest()/buildEndpointUrl()/notifyOrder() + ProductSyncService::
// notifyOrderToPOS() — the outbound HTTP call to UvoPOS's "orders/external" endpoint.
@Component
public class PosClient {

    private static final Logger log = LoggerFactory.getLogger(PosClient.class);

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
    //
    // PROD-13b: y ya no lanza. Devuelve QUÉ pasó, clasificado por lo único que importa para reintentar
    // mientras el POS no confirme su idempotencia (POS-1): si la venta pudo haber llegado. La
    // clasificación vive aquí porque depende de en qué punto del intercambio HTTP se cortó, y eso solo se
    // sabe aquí: con SimpleClientHttpRequestFactory, lo que falla antes de llegar a la respuesta (conectar)
    // sale como ResourceAccessException, y lo que falla al leerla, dentro del exchange.
    public PosDelivery notifyOrder(PosConnection connection, PosOrderPayload payload) {
        String url = buildEndpointUrl(connection, "orders/external");

        try {
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
                    .exchangeForRequiredValue((request, response) -> classify(response));
        } catch (ResponseLostException e) {
            return PosDelivery.uncertain("La petición salió y no llegó respuesta: " + describe(e.getCause()));
        } catch (ResourceAccessException e) {
            if (hasCause(e, ResponseLostException.class)) {
                return PosDelivery.uncertain("La petición salió y no llegó respuesta: " + describe(rootCause(e)));
            }
            return neverReachedThePos(e)
                    ? PosDelivery.notSent("No se pudo conectar con el POS: " + describe(rootCause(e)))
                    : PosDelivery.uncertain("Fallo de red durante el envío: " + describe(rootCause(e)));
        } catch (RuntimeException e) {
            return PosDelivery.uncertain("Error inesperado durante el envío: " + describe(e));
        }
    }

    /**
     * POS-4. Lo que el POS tiene de estos productos (contrato propuesto, ver {@link PosCatalogSnapshot}), para la
     * conciliación.
     *
     * <p>No clasifica los fallos como {@link #notifyOrder}: es una lectura, repetirla no tiene efectos, y la
     * conciliación solo necesita saber si tiene datos o no. Tampoco lanza: un POS caído no puede tumbar la pasada
     * de las demás empresas.
     */
    public PosCatalogSnapshot readCatalog(PosConnection connection, Collection<Long> externalIds) {
        try {
            String url = buildEndpointUrl(connection, "products/snapshot");
            return restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + connection.getApiKey())
                    .header("X-Company-ID", String.valueOf(connection.getCompanyId()))
                    .header("User-Agent", "UvoStore/1.0")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of("product_ids", List.copyOf(externalIds)))
                    .exchangeForRequiredValue((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            return PosCatalogSnapshot.failed("El POS respondió HTTP " + status.value()
                                    + " a la lectura del catálogo");
                        }
                        Map<String, Object> body = readLeniently(response);
                        return body == null
                                ? PosCatalogSnapshot.failed("El POS respondió con un cuerpo ilegible")
                                : PosCatalogSnapshot.parse(body);
                    });
        } catch (RuntimeException e) {
            return PosCatalogSnapshot.failed("No se pudo leer el catálogo del POS: " + describe(rootCause(e)));
        }
    }

    private static PosDelivery classify(ConvertibleClientHttpResponse response) {
        HttpStatusCode status;
        try {
            // Aquí se lee la línea de estado. Un timeout en este punto significa que la petición ya salió
            // entera: el POS pudo haberla procesado.
            status = response.getStatusCode();
        } catch (IOException e) {
            throw new ResponseLostException(e);
        }

        int code = status.value();
        if (status.is2xxSuccessful()) {
            return PosDelivery.sent(readLeniently(response));
        }
        if (code == 408 || code == 429) {
            // El POS dice explícitamente que no la procesó (se cansó de esperar el cuerpo, o pide calma).
            return PosDelivery.notSent("HTTP " + code + ": el POS no procesó la petición");
        }
        if (code == 409) {
            // Puede ser la respuesta del POS a una venta que ya registró con esta misma clave. Qué significa
            // exactamente lo decide el contrato (POS-1); hasta entonces, no se da por entregada ni por fallida.
            return PosDelivery.uncertain("HTTP 409: posible venta ya registrada con esta clave");
        }
        if (status.is4xxClientError()) {
            return PosDelivery.rejected("HTTP " + code + ": el POS rechazó la venta");
        }
        return PosDelivery.uncertain("HTTP " + code + ": el POS falló procesando la venta");
    }

    // Un 2xx es una venta registrada, diga lo que diga el cuerpo. Si no se puede leer, se pierde solo la
    // comprobación de divergencia de stock, que es una observación y no la entrega.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readLeniently(ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(Map.class);
        } catch (RuntimeException e) {
            log.warn("El POS respondió 2xx con un cuerpo ilegible: {}", e.getMessage());
            return null;
        }
    }

    /**
     * La petición no llegó a salir: no hubo conexión. Solo eso es seguro reintentar sin idempotencia
     * confirmada. El timeout de conexión entra aquí —es lo más común con un host caído— y se distingue del
     * de lectura por el mensaje del JDK ("Connect timed out" frente a "Read timed out").
     */
    private static boolean neverReachedThePos(Throwable e) {
        Throwable root = rootCause(e);
        if (root instanceof ConnectException || root instanceof UnknownHostException
                || root instanceof NoRouteToHostException) {
            return true;
        }
        return root instanceof SocketTimeoutException
                && root.getMessage() != null
                && root.getMessage().toLowerCase(Locale.ROOT).contains("connect");
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
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
    // llamaba desde el listener del checkout —antes en el propio hilo de la petición— y una por cada
    // empresa a la que haya que notificar. Ahora sale del despachador del outbox (PROD-13b) y con un tope
    // configurable mucho más bajo: si la plataforma SII resulta ser legítimamente más lenta, se sube la
    // propiedad (y con ella el plazo de apagado, ver application.properties).
    private static org.springframework.http.client.ClientHttpRequestFactory clientRequestFactory(
            int connectTimeoutMs, int readTimeoutMs) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    /** La respuesta se perdió después de enviar la petición entera. Solo existe para salir del exchange. */
    private static final class ResponseLostException extends RuntimeException {
        ResponseLostException(IOException cause) {
            super(cause);
        }
    }
}
