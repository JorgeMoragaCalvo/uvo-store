package org.uvo.uvostore.pos;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.service.pos.PosCatalogSnapshot;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosVariantFields;
import org.uvo.uvostore.service.pos.PosVariants;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POS-4. La lectura masiva del catálogo del POS ({@code PosClient.readCatalog}), con sockets de verdad como
 * {@code PosClientTimeoutTest}: el contrato es una propuesta (POS-1), y esto fija lo que se envía y lo que se acepta.
 *
 * <p>Lo importante además de leer bien es <b>no lanzar</b>: un POS caído o que responde basura tiene que volver
 * como un fallo con su motivo, para que la conciliación lo registre y siga con las demás empresas.
 */
class PosCatalogReadTest {

    private final PosClient client = new PosClient(500, 2000);

    private HttpServer httpServer;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> companyHeader = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    @DisplayName("Pide los ids con las credenciales de la conexión y entiende las filas")
    void sendsTheIdsAndParsesTheRows() throws IOException {
        // Los números llegan como los serialice el POS: 7, 7.0 o "7".
        startServer(200, """
                {"data":[
                  {"product_id":11,"version":5,"stock_web":7,"price":9990,"name":"Polera","description":"Algodón"},
                  {"product_id":"12","version":"6","stock_web":3.0,"price":"1990.50"}
                ]}""");

        PosCatalogSnapshot snapshot = client.readCatalog(connection(), List.of(11L, 12L));

        assertThat(snapshot.isFailed()).isFalse();
        assertThat(requestPath.get()).isEqualTo("/api/v1/products/snapshot");
        assertThat(requestBody.get()).contains("\"product_ids\":[11,12]");
        assertThat(authorization.get()).isEqualTo("Bearer clave-pos");
        assertThat(companyHeader.get()).isEqualTo("77");

        PosCatalogSnapshot.Row first = snapshot.rows().get(11L);
        assertThat(first.version()).isEqualTo(5L);
        assertThat(first.stockWeb()).isEqualTo(7);
        assertThat(first.price()).isEqualByComparingTo("9990");
        assertThat(first.name()).isEqualTo("Polera");
        assertThat(first.description()).isEqualTo("Algodón");

        PosCatalogSnapshot.Row second = snapshot.rows().get(12L);
        assertThat(second.version()).isEqualTo(6L);
        assertThat(second.stockWeb()).isEqualTo(3);
        assertThat(second.price()).isEqualByComparingTo(new BigDecimal("1990.50"));
        assertThat(second.name()).isNull();
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
    }

    @Test
    @DisplayName("POS-5: las variantes de una fila se leen con la misma tolerancia")
    void theVariantsOfARowAreParsed() throws IOException {
        startServer(200, """
                {"data":[
                  {"product_id":11,"version":5,"variants":[
                    {"variant_id":111,"sku":"POL-M","price":9990,"stock_web":4,"attributes":{"Talla":"M","Color":"Azul"}},
                    {"variant_id":"112","sku":"POL-L","price":"10990","stock_web":"dos","attributes":{"Talla":"L"}},
                    "esto no es una variante"
                  ]},
                  {"product_id":12,"version":6,"stock_web":3}
                ]}""");

        PosCatalogSnapshot snapshot = client.readCatalog(connection(), List.of(11L, 12L));

        List<PosVariantFields> variants = snapshot.rows().get(11L).variants();
        assertThat(variants).hasSize(3);
        assertThat(variants.get(0)).isEqualTo(new PosVariantFields(111L, "POL-M", new BigDecimal("9990"), 4,
                Map.of("Talla", "M", "Color", "Azul")));
        assertThat(variants.get(1).variantId()).isEqualTo(112L);
        assertThat(variants.get(1).stock()).as("un stock ilegible queda vacío, sin tumbar la fila").isNull();
        assertThat(variants.get(2).variantId())
                .as("una entrada ilegible queda como una variante vacía, para que la validación la rechace")
                .isNull();
        assertThat(PosVariants.validate(variants)).isNotNull();
        assertThat(snapshot.rows().get(12L).variants()).as("un producto simple no trae variantes").isNull();
    }

    @Test
    @DisplayName("Un error del POS es un fallo con su motivo, no una excepción")
    void aNon2xxIsAFailure() throws IOException {
        startServer(503, "{}");

        PosCatalogSnapshot snapshot = client.readCatalog(connection(), List.of(11L));

        assertThat(snapshot.isFailed()).isTrue();
        assertThat(snapshot.failure()).contains("HTTP 503");
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

    private void startServer(int status, String body) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            companyHeader.set(exchange.getRequestHeaders().getFirst("X-Company-ID"));
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
        // Con /api/v1 al final, como lo escriben algunos comercios: buildEndpointUrl lo quita.
        PosConnection connection = connectionTo(httpServer.getAddress().getPort());
        connection.setApiUrl(connection.getApiUrl() + "/api/v1/");
        return connection;
    }

    private static PosConnection connectionTo(int port) {
        PosConnection connection = new PosConnection();
        connection.setApiUrl("http://localhost:" + port);
        connection.setApiKey("clave-pos");
        connection.setCompanyId(77L);
        return connection;
    }
}
