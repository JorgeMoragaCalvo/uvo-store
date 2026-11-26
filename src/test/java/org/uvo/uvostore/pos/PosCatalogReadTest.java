package org.uvo.uvostore.pos;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.security.UvoHmac;
import org.uvo.uvostore.service.pos.PosCatalogSnapshot;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.support.PosSigning;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POS-4 e IMP-08. La lectura masiva del catálogo del POS ({@code PosClient.readCatalog}, contrato §5), con sockets de
 * verdad como {@code PosClientTimeoutTest}: lo que se envía (firmado), y lo que se acepta.
 *
 * <p>Lo importante además de leer bien es <b>no lanzar</b>: un POS caído o que responde basura tiene que volver como
 * un fallo con su motivo, para que la conciliación lo registre y siga con las demás empresas.
 */
class PosCatalogReadTest {

    private static final String KEY_ID = "pos-77-tienda";
    private static final String SECRET = PosSigning.secret("lectura-77");

    private final PosClient client = new PosClient(500, 2000, PosSigning.fixedOutbound(KEY_ID, SECRET));

    private HttpServer httpServer;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<Headers> headers = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    @DisplayName("Pide los ids firmados con la llave tienda→POS y entiende las filas y missing")
    void sendsTheIdsAndParsesTheRows() throws IOException {
        // Los números llegan como los serialice el POS: 7, 7.0 o "7".
        startServer(200, """
                {"success":true,"data":[
                  {"product_id":11,"sku":"POL-M","version":5,"stock_web":7,"price":9990,"name":"Polera","tax_exempt":false},
                  {"product_id":"12","version":"6","stock_web":3.0,"price":"1990.50","tax_exempt":true}
                ],"missing":[13]}""");

        PosCatalogSnapshot snapshot = client.readCatalog(connection(), List.of(11L, 12L, 13L));

        assertThat(snapshot.isFailed()).isFalse();
        assertThat(requestPath.get()).isEqualTo("/api/v1/products/snapshot");
        assertThat(requestBody.get()).isEqualTo("{\"product_ids\":[11,12,13]}");
        Headers sent = headers.get();
        assertThat(sent.getFirst("X-Company-ID")).isEqualTo("77");
        assertThat(sent.getFirst("X-Uvo-Contract")).isEqualTo("1");
        assertThat(sent.getFirst("X-Uvo-Key-Id")).isEqualTo(KEY_ID);
        assertThat(sent.getFirst("Authorization")).isNull();
        String canonical = UvoHmac.canonical("POST", "/api/v1/products/snapshot",
                Long.parseLong(sent.getFirst("X-Uvo-Timestamp")), sent.getFirst("X-Uvo-Nonce"), KEY_ID,
                requestBody.get().getBytes(StandardCharsets.UTF_8));
        assertThat(UvoHmac.verify(SECRET, canonical, sent.getFirst("X-Uvo-Signature"))).isTrue();

        PosCatalogSnapshot.Row first = snapshot.rows().get(11L);
        assertThat(first.version()).isEqualTo(5L);
        assertThat(first.stockWeb()).isEqualTo(7);
        assertThat(first.price()).isEqualByComparingTo("9990");
        assertThat(first.name()).isEqualTo("Polera");
        assertThat(first.sku()).isEqualTo("POL-M");
        assertThat(first.taxExempt()).isFalse();

        PosCatalogSnapshot.Row second = snapshot.rows().get(12L);
        assertThat(second.version()).isEqualTo(6L);
        assertThat(second.stockWeb()).isEqualTo(3);
        assertThat(second.price()).isEqualByComparingTo(new BigDecimal("1990.50"));
        assertThat(second.name()).isNull();
        assertThat(second.taxExempt()).isTrue();

        assertThat(snapshot.missing()).containsExactly(13L);
        assertThat(snapshot.unaccounted(List.of(11L, 12L, 13L, 14L))).as("§5: ni en data ni en missing")
                .containsExactly(14L);
    }

    @Test
    @DisplayName("Un campo que no se entiende queda vacío, y una fila sin product_id se ignora")
    void anUnreadableFieldOnlyBlanksThatField() throws IOException {
        startServer(200, """
                {"data":[
                  {"product_id":11,"version":"cinco","stock_web":2.5,"price":9990},
                  {"version":1,"stock_web":4}
                ]}""");

        PosCatalogSnapshot snapshot = client.readCatalog(connection(), List.of(11L));

        assertThat(snapshot.rows()).containsOnlyKeys(11L);
        PosCatalogSnapshot.Row row = snapshot.rows().get(11L);
        assertThat(row.version()).as("sin versión, el catálogo no se concilia").isNull();
        assertThat(row.stockWeb()).as("2.5 no es un stock").isNull();
        assertThat(row.price()).isEqualByComparingTo("9990");
        assertThat(snapshot.missing()).isEmpty();
    }

    @Test
    @DisplayName("Un error del POS es un fallo con su motivo y su código, no una excepción")
    void aNon2xxIsAFailure() throws IOException {
        startServer(422, "{\"success\":false,\"error\":{\"code\":\"VALIDATION\",\"message\":\"más de 200\"}}");

        PosCatalogSnapshot snapshot = client.readCatalog(connection(), List.of(11L));

        assertThat(snapshot.isFailed()).isTrue();
        assertThat(snapshot.failure()).contains("HTTP 422").contains("VALIDATION");
    }

    @Test
    @DisplayName("Un cuerpo ilegible o sin data también")
    void anUnreadableBodyIsAFailure() throws IOException {
        startServer(200, "esto no es json");
        assertThat(client.readCatalog(connection(), List.of(11L)).failure()).contains("ilegible");
        httpServer.stop(0);

        startServer(200, "{\"ok\":true}");
        assertThat(client.readCatalog(connection(), List.of(11L)).failure()).contains("data");
    }

    @Test
    @DisplayName("Un POS que no responde es un fallo, no una excepción")
    void aRefusedConnectionIsAFailure() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }

        PosCatalogSnapshot snapshot = client.readCatalog(connectionTo(closedPort), List.of(11L));

        assertThat(snapshot.isFailed()).isTrue();
        assertThat(snapshot.failure()).contains("No se pudo leer el catálogo del POS");
    }

    @Test
    @DisplayName("Sin llave tienda→POS no sale nada: es un fallo")
    void withoutAnOutboundKeyNothingIsSent() {
        PosClient keyless = new PosClient(500, 2000, connection -> java.util.Optional.empty());

        assertThat(keyless.readCatalog(connectionTo(1), List.of(11L)).failure()).contains("llave");
    }

    private void startServer(int status, String body) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getRawPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            headers.set(exchange.getRequestHeaders());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        httpServer.start();
    }

    private PosConnection connection() {
        // Con /api/v1 al final, como lo escriben algunos comercios: el cliente lo quita.
        PosConnection connection = connectionTo(httpServer.getAddress().getPort());
        connection.setApiUrl(connection.getApiUrl() + "/api/v1/");
        return connection;
    }

    private static PosConnection connectionTo(int port) {
        PosConnection connection = new PosConnection();
        connection.setApiUrl("http://localhost:" + port);
        connection.setCompanyId(77L);
        return connection;
    }
}
