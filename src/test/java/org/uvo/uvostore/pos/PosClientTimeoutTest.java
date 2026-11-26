package org.uvo.uvostore.pos;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.security.UvoHmac;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosDelivery;
import org.uvo.uvostore.service.pos.PosLookup;
import org.uvo.uvostore.support.PosSigning;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1, PROD-13b e IMP-08. Cómo se clasifica cada respuesta del POS a una venta (contrato §10) y a su consulta (§9), con
 * sockets de verdad y no simulando excepciones: lo que importa es qué lanza realmente {@code RestClient} con
 * {@code SimpleClientHttpRequestFactory} en cada punto del intercambio. Las respuestas son los ejemplos del contrato
 * ({@code docs/contrato-pos-ecommerce/ejemplos/}).
 */
class PosClientTimeoutTest {

    private static final int READ_TIMEOUT_MS = 800;
    private static final String KEY_ID = "pos-1-tienda";
    private static final String SECRET = PosSigning.secret("venta-1");
    private static final String KEY = "ORD-1:1";
    private static final String BODY = "{\"order_number\":\"ORD-1\"}";
    private static final Path EXAMPLES = Path.of("docs", "contrato-pos-ecommerce", "ejemplos");

    private final PosClient client = new PosClient(500, READ_TIMEOUT_MS, PosSigning.fixedOutbound(KEY_ID, SECRET));

    private ServerSocket deafServer;
    private Thread acceptLoop;
    private final List<Socket> accepted = new ArrayList<>();
    private HttpServer httpServer;
    private final AtomicReference<String> rawPath = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<byte[]> received = new AtomicReference<>();
    private final AtomicReference<Headers> headers = new AtomicReference<>();

    @AfterEach
    void stopServers() throws IOException {
        for (Socket socket : accepted) {
            socket.close();
        }
        if (deafServer != null) {
            deafServer.close();
            acceptLoop.interrupt();
        }
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    // --- R1: los cortes de red ------------------------------------------------------------------------

    @Test
    @DisplayName("Un POS que acepta y no contesta corta en el tope configurado, y la venta queda incierta")
    void aDeafPosIsCutOffAndTheSaleIsUncertain() throws IOException {
        startDeafServer();

        Instant start = Instant.now();
        PosDelivery delivery = client.notifyOrder(connectionTo(deafServer.getLocalPort()), KEY, BODY);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
        assertThat(delivery.outcome()).isEqualTo(PosDelivery.Outcome.UNCERTAIN);
    }

    @Test
    @DisplayName("Un puerto cerrado es 'no enviado': no hubo conexión, reintentar es seguro")
    void aRefusedConnectionIsNotSent() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }

        assertThat(client.notifyOrder(connectionTo(closedPort), KEY, BODY).outcome())
                .isEqualTo(PosDelivery.Outcome.NOT_SENT);
    }

    @Test
    @DisplayName("Sin llave tienda→POS no sale nada: 'no enviado'")
    void withoutAnOutboundKeyNothingIsSent() {
        PosClient keyless = new PosClient(500, READ_TIMEOUT_MS, connection -> java.util.Optional.empty());

        assertThat(keyless.notifyOrder(connectionTo(1), KEY, BODY).outcome()).isEqualTo(PosDelivery.Outcome.NOT_SENT);
    }

    // --- §3: lo que sale ------------------------------------------------------------------------------

    @Test
    @DisplayName("La venta sale firmada sobre los bytes exactos, con las cabeceras del contrato")
    void theSaleIsSignedOverTheExactBytes() throws IOException {
        startHttpServer(201, example("venta.201-registrada.response.json"), null);

        PosDelivery delivery = client.notifyOrder(connection(), KEY, BODY);

        assertThat(delivery.outcome()).isEqualTo(PosDelivery.Outcome.SENT);
        assertThat(method.get()).isEqualTo("POST");
        assertThat(rawPath.get()).isEqualTo("/api/v1/orders/external");
        assertThat(new String(received.get(), StandardCharsets.UTF_8)).isEqualTo(BODY);
        Headers sent = headers.get();
        assertThat(sent.getFirst("Idempotency-Key")).isEqualTo(KEY);
        assertThat(sent.getFirst("X-Company-ID")).isEqualTo("1");
        assertThat(sent.getFirst("X-Uvo-Contract")).isEqualTo("1");
        assertThat(sent.getFirst("Authorization")).isNull();
        assertThat(verifies("POST", rawPath.get(), received.get(), sent)).isTrue();
    }

    // --- §10: la tabla de la venta ---------------------------------------------------------------------

    @Test
    @DisplayName("201 y 200 repetida son entregadas, con el cuerpo para comparar el stock")
    void registeredAndRepeatedAreSent() throws IOException {
        startHttpServer(200, example("venta.200-repetida.response.json"), null);

        PosDelivery delivery = client.notifyOrder(connection(), KEY, BODY);

        assertThat(delivery.outcome()).isEqualTo(PosDelivery.Outcome.SENT);
        assertThat(delivery.response()).containsKey("data");
    }

    @Test
    @DisplayName("Un 2xx es una venta registrada, aunque el cuerpo no se pueda leer")
    void a2xxIsSentEvenWithAGarbageBody() throws IOException {
        startHttpServer(200, "esto no es json", null);

        PosDelivery delivery = client.notifyOrder(connection(), KEY, BODY);

        assertThat(delivery.outcome()).isEqualTo(PosDelivery.Outcome.SENT);
        assertThat(delivery.response()).isNull();
    }

    @Test
    @DisplayName("409 IN_PROGRESS es 'no enviado' y respeta Retry-After")
    void inProgressIsNotSentWithRetryAfter() throws IOException {
        startHttpServer(409, example("venta.409-en-curso.response.json"), "7");

        PosDelivery delivery = client.notifyOrder(connection(), KEY, BODY);

        assertThat(delivery.outcome()).isEqualTo(PosDelivery.Outcome.NOT_SENT);
        assertThat(delivery.errorCode()).isEqualTo("IN_PROGRESS");
        assertThat(delivery.retryAfter()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("409 IDEMPOTENCY_CONFLICT es un rechazo: lo revisa una persona")
    void anIdempotencyConflictIsRejected() throws IOException {
        startHttpServer(409, example("venta.409-conflicto.response.json"), null);

        PosDelivery delivery = client.notifyOrder(connection(), KEY, BODY);

        assertThat(delivery.outcome()).isEqualTo(PosDelivery.Outcome.REJECTED);
        assertThat(delivery.detail()).contains("IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("Un 409 sin un código del contrato es incierto")
    void a409WithoutACodeIsUncertain() throws IOException {
        startHttpServer(409, "{}", null);

        assertThat(client.notifyOrder(connection(), KEY, BODY).outcome()).isEqualTo(PosDelivery.Outcome.UNCERTAIN);
    }

    @Test
    @DisplayName("422 OUT_OF_STOCK y VALIDATION son rechazos con su código y su mensaje")
    void a422IsRejectedWithItsCode() throws IOException {
        startHttpServer(422, example("venta.422-sin-stock.response.json"), null);
        PosDelivery outOfStock = client.notifyOrder(connection(), KEY, BODY);
        httpServer.stop(0);
        startHttpServer(422, example("venta.422-validacion.response.json"), null);
        PosDelivery validation = client.notifyOrder(connection(), KEY, BODY);

        assertThat(outOfStock.outcome()).isEqualTo(PosDelivery.Outcome.REJECTED);
        assertThat(outOfStock.errorCode()).isEqualTo("OUT_OF_STOCK");
        assertThat(outOfStock.detail()).startsWith("HTTP 422 OUT_OF_STOCK");
        assertThat(validation.errorCode()).isEqualTo("VALIDATION");
    }

    @Test
    @DisplayName("401 y 403 son rechazos")
    void authFailuresAreRejected() throws IOException {
        startHttpServer(401, "{\"success\":false,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"firma\"}}", null);

        assertThat(client.notifyOrder(connection(), KEY, BODY).outcome()).isEqualTo(PosDelivery.Outcome.REJECTED);
    }

    @Test
    @DisplayName("Un 5xx es incierto: el POS pudo haber registrado la venta antes de fallar")
    void a5xxIsUncertain() throws IOException {
        startHttpServer(500, "{}", null);

        assertThat(client.notifyOrder(connection(), KEY, BODY).outcome()).isEqualTo(PosDelivery.Outcome.UNCERTAIN);
    }

    @Test
    @DisplayName("Un 429 es 'no enviado': el POS dice explícitamente que no la procesó")
    void a429IsNotSent() throws IOException {
        startHttpServer(429, "{}", "30");

        PosDelivery delivery = client.notifyOrder(connection(), KEY, BODY);

        assertThat(delivery.outcome()).isEqualTo(PosDelivery.Outcome.NOT_SENT);
        assertThat(delivery.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    // --- §9: la consulta -------------------------------------------------------------------------------

    @Test
    @DisplayName("La consulta va por GET con la clave codificada (%3A), y se firma así")
    void theLookupSignsTheEncodedPath() throws IOException {
        startHttpServer(200, example("consulta.200-registrada.response.json"), null);

        PosLookup lookup = client.lookup(connection(), "UVO-20261120-0001:12");

        assertThat(method.get()).isEqualTo("GET");
        assertThat(rawPath.get()).isEqualTo("/api/v1/orders/external/UVO-20261120-0001%3A12");
        assertThat(received.get()).isEmpty();
        assertThat(verifies("GET", rawPath.get(), new byte[0], headers.get())).isTrue();
        assertThat(lookup.status()).isEqualTo(PosLookup.Status.REGISTERED);
        assertThat(lookup.saleId()).isNotNull();
        assertThat(lookup.bodySha256()).isNotBlank();
    }

    @Test
    @DisplayName("404 NOT_PROCESSED es una promesa; un 404 cualquiera no significa nada")
    void onlyNotProcessedMeansNotProcessed() throws IOException {
        startHttpServer(404, example("consulta.404-no-procesada.response.json"), null);
        PosLookup notProcessed = client.lookup(connection(), KEY);
        httpServer.stop(0);
        startHttpServer(404, "<html>Not Found</html>", null);
        PosLookup proxy = client.lookup(connection(), KEY);

        assertThat(notProcessed.status()).isEqualTo(PosLookup.Status.NOT_PROCESSED);
        assertThat(proxy.status()).isEqualTo(PosLookup.Status.UNKNOWN);
    }

    @Test
    @DisplayName("La consulta en curso es IN_PROGRESS, y sin respuesta es UNKNOWN")
    void inProgressAndNoAnswer() throws IOException {
        startHttpServer(409, "{\"success\":false,\"error\":{\"code\":\"IN_PROGRESS\",\"message\":\"en curso\"}}", "3");
        PosLookup inProgress = client.lookup(connection(), KEY);
        startDeafServer();
        PosLookup deaf = client.lookup(connectionTo(deafServer.getLocalPort()), KEY);

        assertThat(inProgress.status()).isEqualTo(PosLookup.Status.IN_PROGRESS);
        assertThat(inProgress.retryAfter()).isEqualTo(Duration.ofSeconds(3));
        assertThat(deaf.status()).isEqualTo(PosLookup.Status.UNKNOWN);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static boolean verifies(String method, String path, byte[] body, Headers sent) {
        String canonical = UvoHmac.canonical(method, path, Long.parseLong(sent.getFirst("X-Uvo-Timestamp")),
                sent.getFirst("X-Uvo-Nonce"), sent.getFirst("X-Uvo-Key-Id"), body);
        return KEY_ID.equals(sent.getFirst("X-Uvo-Key-Id"))
                && UvoHmac.verify(SECRET, canonical, sent.getFirst("X-Uvo-Signature"));
    }

    private static String example(String name) throws IOException {
        return Files.readString(EXAMPLES.resolve(name));
    }

    private void startDeafServer() throws IOException {
        deafServer = new ServerSocket(0);
        acceptLoop = new Thread(() -> {
            while (!deafServer.isClosed()) {
                try {
                    // Aceptar y no responder: la conexión queda abierta y el cliente esperando.
                    accepted.add(deafServer.accept());
                } catch (IOException e) {
                    return;
                }
            }
        });
        acceptLoop.setDaemon(true);
        acceptLoop.start();
    }

    private void startHttpServer(int status, String body, String retryAfter) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            // Leer la petición entera antes de responder: cerrar con datos sin leer haría que el sistema
            // operativo resetee la conexión, y el test mediría eso en vez de la respuesta.
            received.set(exchange.getRequestBody().readAllBytes());
            rawPath.set(exchange.getRequestURI().getRawPath());
            method.set(exchange.getRequestMethod());
            headers.set(exchange.getRequestHeaders());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (retryAfter != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter);
            }
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        httpServer.start();
    }

    private PosConnection connection() {
        return connectionTo(httpServer.getAddress().getPort());
    }

    private static PosConnection connectionTo(int port) {
        PosConnection connection = new PosConnection();
        connection.setApiUrl("http://localhost:" + port);
        connection.setCompanyId(1L);
        return connection;
    }
}
