package org.uvo.uvostore.pos;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.uvo.uvostore.config.LogContext;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosDelivery;
import org.uvo.uvostore.support.PosSigning;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-15b e IMP-08. El id con el que se sigue una venta llega al POS como {@code X-Request-Id} en cada llamada, con el
 * formato del contrato (§11: {@code [A-Za-z0-9-]{8,64}}). Con sockets de verdad, como {@code PosCatalogReadTest}.
 */
class PosClientRequestIdTest {

    private final PosClient client = new PosClient(500, 2000,
            PosSigning.fixedOutbound("pos-77-tienda", PosSigning.secret("request-id")));
    private final List<String> received = new CopyOnWriteArrayList<>();
    private HttpServer httpServer;

    @BeforeEach
    void startServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("X-Request-Id");
            received.add(header == null ? "<sin cabecera>" : header);
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        httpServer.start();
    }

    @AfterEach
    void stop() {
        httpServer.stop(0);
        MDC.clear();
    }

    @Test
    @DisplayName("La venta y la lectura del catálogo llevan el id del MDC")
    void bothCallsCarryTheIdFromTheMdc() {
        LogContext.putRequestId("it-venta-15b-0001");

        PosDelivery delivery = client.notifyOrder(connection(), "ORD-1:77", "{\"x\":1}");
        LogContext.putRequestId("job-0123456789ab");
        client.readCatalog(connection(), List.of(11L));

        assertThat(delivery.outcome()).isEqualTo(PosDelivery.Outcome.SENT);
        assertThat(received).containsExactly("it-venta-15b-0001", "job-0123456789ab");
    }

    @Test
    @DisplayName("Un id con punto o guion bajo sale con guiones: el contrato solo admite letras, dígitos y '-'")
    void anIdOutsideTheContractFormatIsAdapted() {
        LogContext.putRequestId("proxy.id_00001");

        client.readCatalog(connection(), List.of(11L));

        assertThat(received).containsExactly("proxy-id-00001");
    }

    @Test
    @DisplayName("Sin id en el MDC va uno nuevo: el contrato pide que cada petición lleve uno")
    void withoutAnIdANewOneIsSent() {
        client.readCatalog(connection(), List.of(11L));

        assertThat(received).hasSize(1);
        assertThat(received.getFirst()).matches("[A-Za-z0-9-]{8,64}");
    }

    private PosConnection connection() {
        PosConnection connection = new PosConnection();
        connection.setCompanyId(77L);
        connection.setApiUrl("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/api/v1");
        return connection;
    }
}
