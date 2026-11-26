package org.uvo.uvostore.service.pos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.uvo.uvostore.config.LogContext;
import org.uvo.uvostore.config.RecoveryModePolicy;
import org.uvo.uvostore.config.RequestIdFilter;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.security.UvoHmac;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Las llamadas de la tienda al POS (contrato UvoPOS ↔ uvo-store v1): registrar una venta (§7), consultarla (§9) y leer
 * el catálogo para conciliar (§5).
 *
 * <p>IMP-08. Cada petición va firmada con UVO-HMAC ({@link UvoHmac}) con la llave tienda→POS vigente de la conexión,
 * con un nonce nuevo, y lleva {@code X-Uvo-Contract: 1}. Se firma la ruta exacta que viaja y los bytes exactos del
 * cuerpo: por eso el cuerpo se manda como bytes y la URI ya armada, para que nada la vuelva a codificar.
 */
@Component
public class PosClient {

    private static final Logger log = LoggerFactory.getLogger(PosClient.class);

    private static final Pattern TRAILING_API_VERSION = Pattern.compile("/api/v\\d+$");

    /** El formato de {@code X-Request-Id} que pide el contrato (§11). */
    private static final Pattern CONTRACT_REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private static final String SALES_PATH = "orders/external";

    private final RestClient restClient;
    private final RecoveryModePolicy recoveryMode;
    private final PosSigningKeys signingKeys;

    /** Para las pruebas: sin modo recuperación y con una llave fija. */
    public PosClient(int connectTimeoutMs, int readTimeoutMs, PosSigningKeys signingKeys) {
        this(connectTimeoutMs, readTimeoutMs, RecoveryModePolicy.inactive(), signingKeys);
    }

    @Autowired
    public PosClient(@Value("${app.pos.connect-timeout-ms:5000}") int connectTimeoutMs,
                     @Value("${app.pos.read-timeout-ms:20000}") int readTimeoutMs,
                     RecoveryModePolicy recoveryMode, PosSigningKeys signingKeys) {
        this.recoveryMode = recoveryMode;
        this.signingKeys = signingKeys;
        this.restClient = RestClient.builder()
                .requestFactory(clientRequestFactory(connectTimeoutMs, readTimeoutMs))
                .requestInterceptor((request, body, execution) -> {
                    // PROD-15b. El id con el que se sigue esta llamada en nuestros logs: el de la petición que
                    // encoló la venta (OutboxDispatcher lo pone desde la fila) o el de la pasada del job. El POS lo
                    // registra y lo devuelve, así una venta se sigue entre los dos sistemas con un solo id.
                    request.getHeaders().set(RequestIdFilter.HEADER, contractRequestId(LogContext.requestId()));
                    return execution.execute(request, body);
                })
                .build();
    }

    /**
     * Registra una venta (§7). {@code json} son los bytes exactos que se firman y se mandan: la fila del outbox los
     * fija la primera vez, y cada reintento manda los mismos.
     *
     * <p>PROD-13b: no lanza. Devuelve qué pasó, clasificado como pide el §10: si la venta pudo haber llegado. Lo que
     * falla antes de llegar a la respuesta (conectar) sale como {@code ResourceAccessException}, y lo que falla al
     * leerla, dentro del exchange.
     */
    public PosDelivery notifyOrder(PosConnection connection, String idempotencyKey, String json) {
        // IMP-09. Lanza a propósito, en vez de devolver un PosDelivery: con el modo recuperación nadie debería llegar
        // aquí (el outbox no corre), y si alguien llega, la venta no puede salir con un resultado que parezca normal.
        // La consulta y la lectura del catálogo sí se permiten: no tienen efectos en el POS.
        recoveryMode.requireInactive("la venta al POS");
        Optional<PosSigningKeys.SigningKey> key = signingKeys.outboundFor(connection);
        if (key.isEmpty()) {
            return PosDelivery.notSent("La conexión no tiene una llave tienda→POS vigente");
        }
        URI uri = endpoint(connection, SALES_PATH);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        try {
            return signed(restClient.post().uri(uri), "POST", uri, body, connection, key.get())
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
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
     * Consulta si el POS registró una venta (§9). Es una lectura sin efectos: se permite en modo recuperación y no
     * lanza. Cualquier respuesta que no sea una de las tres que define el contrato es {@link PosLookup.Status#UNKNOWN}:
     * un 404 sin {@code NOT_PROCESSED} (un proxy, una ruta que no existe) no significa nada.
     */
    public PosLookup lookup(PosConnection connection, String idempotencyKey) {
        Optional<PosSigningKeys.SigningKey> key = signingKeys.outboundFor(connection);
        if (key.isEmpty()) {
            return PosLookup.unknown("La conexión no tiene una llave tienda→POS vigente");
        }
        // La clave va codificada en la ruta (':' viaja como %3A) y se firma así, codificada.
        URI uri = endpoint(connection, SALES_PATH + "/" + URLEncoder.encode(idempotencyKey, StandardCharsets.UTF_8));
        try {
            return signed(restClient.method(org.springframework.http.HttpMethod.GET).uri(uri), "GET", uri, new byte[0],
                    connection, key.get())
                    .exchangeForRequiredValue((request, response) -> classifyLookup(response));
        } catch (RuntimeException e) {
            return PosLookup.unknown("No se pudo consultar la venta: " + describe(rootCause(e)));
        }
    }

    /**
     * POS-4. Lo que el POS tiene de estos productos (§5, {@link PosCatalogSnapshot}), para la conciliación. Hasta 200
     * por petición.
     *
     * <p>No clasifica los fallos como {@link #notifyOrder}: es una lectura, repetirla no tiene efectos, y la
     * conciliación solo necesita saber si tiene datos o no. Tampoco lanza: un POS caído no puede tumbar la pasada
     * de las demás empresas.
     */
    public PosCatalogSnapshot readCatalog(PosConnection connection, Collection<Long> externalIds) {
        Optional<PosSigningKeys.SigningKey> key = signingKeys.outboundFor(connection);
        if (key.isEmpty()) {
            return PosCatalogSnapshot.failed("La conexión no tiene una llave tienda→POS vigente");
        }
        try {
            URI uri = endpoint(connection, "products/snapshot");
            byte[] body = ("{\"product_ids\":[" + externalIds.stream().map(String::valueOf)
                    .collect(Collectors.joining(",")) + "]}").getBytes(StandardCharsets.UTF_8);
            return signed(restClient.post().uri(uri), "POST", uri, body, connection, key.get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchangeForRequiredValue((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            ErrorBody error = ErrorBody.of(readLeniently(response));
                            return PosCatalogSnapshot.failed("El POS respondió HTTP " + status.value()
                                    + error.suffix() + " a la lectura del catálogo");
                        }
                        Map<String, Object> parsed = readLeniently(response);
                        return parsed == null
                                ? PosCatalogSnapshot.failed("El POS respondió con un cuerpo ilegible")
                                : PosCatalogSnapshot.parse(parsed);
                    });
        } catch (RuntimeException e) {
            return PosCatalogSnapshot.failed("No se pudo leer el catálogo del POS: " + describe(rootCause(e)));
        }
    }

    /** Las cabeceras de la firma (§3), con un nonce y un timestamp nuevos en cada petición. */
    private static <S extends RestClient.RequestHeadersSpec<S>> S signed(S spec, String method, URI uri, byte[] body,
                                                                         PosConnection connection,
                                                                         PosSigningKeys.SigningKey key) {
        long timestamp = System.currentTimeMillis() / 1000;
        String nonce = UvoHmac.newNonce();
        String canonical = UvoHmac.canonical(method, uri.getRawPath(), timestamp, nonce, key.keyId(), body);
        return spec
                .header(UvoHmac.HEADER_KEY_ID, key.keyId())
                .header(UvoHmac.HEADER_TIMESTAMP, Long.toString(timestamp))
                .header(UvoHmac.HEADER_NONCE, nonce)
                .header(UvoHmac.HEADER_SIGNATURE, UvoHmac.sign(key.secret(), canonical))
                .header(UvoHmac.HEADER_COMPANY, String.valueOf(connection.getCompanyId()))
                .header(UvoHmac.HEADER_CONTRACT, UvoHmac.CONTRACT_VERSION)
                .header("User-Agent", "UvoStore/1.0")
                .accept(MediaType.APPLICATION_JSON);
    }

    /** §10, la tabla de la venta. */
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
            // 201 registrada, 200 la misma venta repetida (§8). Un cuerpo ilegible no cambia eso: solo se pierde la
            // comparación de stock, que es una observación y no la entrega.
            return PosDelivery.sent(readLeniently(response));
        }
        ErrorBody error = ErrorBody.of(readLeniently(response));
        Duration retryAfter = retryAfter(response);
        if (code == 409 && "IN_PROGRESS".equals(error.code())) {
            // La misma clave se está procesando ahora: no se registró nada nuevo con esta petición.
            return PosDelivery.notSent("HTTP 409 IN_PROGRESS: el POS está procesando esta venta", error.code(), retryAfter);
        }
        if (code == 408 || code == 429) {
            // El POS dice explícitamente que no la procesó (se cansó de esperar el cuerpo, o pide calma).
            return PosDelivery.notSent("HTTP " + code + error.suffix() + ": el POS no procesó la petición",
                    error.code(), retryAfter);
        }
        if (code == 409 && "IDEMPOTENCY_CONFLICT".equals(error.code())) {
            return PosDelivery.rejected("HTTP 409" + error.suffix(), error.code());
        }
        if (code == 409) {
            // Un 409 sin un código conocido no dice si la venta entró.
            return PosDelivery.uncertain("HTTP 409" + error.suffix() + ": respuesta sin un código del contrato");
        }
        if (status.is4xxClientError()) {
            // 400, 401, 403, 422 (OUT_OF_STOCK, VALIDATION): el POS no registró nada y repetirla igual no sirve.
            return PosDelivery.rejected("HTTP " + code + error.suffix(), error.code());
        }
        return PosDelivery.uncertain("HTTP " + code + error.suffix() + ": el POS falló procesando la venta");
    }

    /** §9. */
    private static PosLookup classifyLookup(ConvertibleClientHttpResponse response) {
        HttpStatusCode status;
        try {
            status = response.getStatusCode();
        } catch (IOException e) {
            return PosLookup.unknown("Sin respuesta a la consulta: " + describe(e));
        }
        Map<String, Object> body = readLeniently(response);
        int code = status.value();
        // {"success": true, "data": {"status": "REGISTERED", "sale_id", "body_sha256", ...}} (ejemplo consulta.200).
        Map<?, ?> data = body != null && body.get("data") instanceof Map<?, ?> fields ? fields : body;
        if (code == 200 && data != null && "REGISTERED".equals(data.get("status"))) {
            return new PosLookup(PosLookup.Status.REGISTERED, asLong(data.get("sale_id")),
                    data.get("body_sha256") instanceof String hash ? hash : null, null, "REGISTERED");
        }
        ErrorBody error = ErrorBody.of(body);
        if (code == 404 && "NOT_PROCESSED".equals(error.code())) {
            return new PosLookup(PosLookup.Status.NOT_PROCESSED, null, null, null, "NOT_PROCESSED");
        }
        if (code == 409 && "IN_PROGRESS".equals(error.code())) {
            return new PosLookup(PosLookup.Status.IN_PROGRESS, null, null, retryAfter(response), "IN_PROGRESS");
        }
        return PosLookup.unknown("La consulta respondió HTTP " + code + error.suffix());
    }

    /** {@code {"success": false, "error": {"code", "message"}}} (§11). */
    private record ErrorBody(String code, String message) {

        @SuppressWarnings("unchecked")
        static ErrorBody of(Map<String, Object> body) {
            if (body != null && body.get("error") instanceof Map<?, ?> error) {
                Map<String, Object> fields = (Map<String, Object>) error;
                return new ErrorBody(fields.get("code") instanceof String c ? c : null,
                        fields.get("message") instanceof String m ? m : null);
            }
            return new ErrorBody(null, null);
        }

        /** " OUT_OF_STOCK: texto" para el detalle que ve una persona en «Envíos fallidos». */
        String suffix() {
            if (code == null && message == null) {
                return "";
            }
            String text = message == null ? "" : ": " + truncate(message);
            return " " + (code == null ? "" : code) + text;
        }

        private static String truncate(String text) {
            return text.length() > 300 ? text.substring(0, 300) + "…" : text;
        }
    }

    private static Duration retryAfter(ConvertibleClientHttpResponse response) {
        String value = response.getHeaders().getFirst("Retry-After");
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Duration.ofSeconds(Math.min(seconds, 3600)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Un cuerpo ilegible es null: quien llama decide qué significa.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readLeniently(ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(Map.class);
        } catch (RuntimeException e) {
            log.warn("El POS respondió con un cuerpo ilegible: {}", e.getMessage());
            return null;
        }
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * El {@code X-Request-Id} que sale. El contrato pide {@code [A-Za-z0-9-]{8,64}} y {@code RequestIdFilter} admite
     * además {@code .} y {@code _}: se cambian por {@code -}, así el id sigue reconociéndose en nuestros logs.
     */
    static String contractRequestId(String requestId) {
        if (requestId != null) {
            String candidate = requestId.replace('.', '-').replace('_', '-');
            if (CONTRACT_REQUEST_ID.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return UUID.randomUUID().toString();
    }

    /**
     * La petición no llegó a salir: no hubo conexión. El timeout de conexión entra aquí —es lo más común con un host
     * caído— y se distingue del de lectura por el mensaje del JDK ("Connect timed out" frente a "Read timed out").
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

    // La URL base de la conexión sin una /api/vN final, más /api/v1/<endpoint>. Si la base tiene una ruta propia
    // (https://pos.ejemplo.cl/alfoz), esa ruta viaja y se firma también: se firma getRawPath de esta URI.
    private static URI endpoint(PosConnection connection, String endpoint) {
        String apiUrl = connection.getApiUrl();
        String trimmed = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        String baseUrl = TRAILING_API_VERSION.matcher(trimmed).replaceAll("");
        return URI.create(baseUrl + "/api/v1/" + endpoint);
    }

    // R1: el tope de lectura (20 s, §11) es configurable: si el POS resulta ser legítimamente más lento, se sube la
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
