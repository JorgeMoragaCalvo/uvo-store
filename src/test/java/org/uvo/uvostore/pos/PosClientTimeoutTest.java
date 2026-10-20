package org.uvo.uvostore.pos;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosDelivery;
import org.uvo.uvostore.service.pos.PosOrderPayload;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1. El fallo que esto reproducía no es "el POS devuelve un error": es "el POS acepta la conexión y no
 * contesta nunca". No lanza nada, no aparece en ningún log, y con el read timeout de 120s que había aquí —y
 * la llamada dentro del hilo de la petición— cada checkout se quedaba dos minutos esperando por cada empresa
 * a notificar.
 *
 * <p>PROD-13b: y ahora, además, <b>cómo se clasifica cada corte</b>, que es lo que decide si una venta se
 * reintenta. Con sockets de verdad y no simulando excepciones, porque lo que importa es qué lanza realmente
 * {@code RestClient} con {@code SimpleClientHttpRequestFactory} en cada punto del intercambio, y eso no se
 * puede adivinar: un timeout de lectura, por ejemplo, NO sale como {@code ResourceAccessException}.
 */
class PosClientTimeoutTest {

    private static final int READ_TIMEOUT_MS = 800;

    private final PosClient client = new PosClient(500, READ_TIMEOUT_MS);

    private ServerSocket deafServer;
    private Thread acceptLoop;
    private final List<Socket> accepted = new ArrayList<>();
    private HttpServer httpServer;

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

    @Test
    @DisplayName("Un POS que acepta y no contesta corta en el tope configurado, y la venta queda incierta")
    void aDeafPosIsCutOffAndTheSaleIsUncertain() throws IOException {
        startDeafServer();

        Instant start = Instant.now();
        PosDelivery delivery = client.notifyOrder(connectionTo(deafServer.getLocalPort()), payload());
        Duration elapsed = Duration.between(start, Instant.now());

        // El margen es ancho a propósito (la máquina puede ir lenta); lo que este test tiene que
        // distinguir es "cortó" de "no cortó". Con el valor anterior fijo en el código —120s— esto
        // no terminaba.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
        // La petición salió entera y no hubo respuesta: el POS pudo haberla registrado. Reintentar sin
        // idempotencia confirmada emitiría un segundo documento.
        assertThat(delivery.outcome()).isEqualTo(PosDelivery.Outcome.UNCERTAIN);
    }

    @Test
    @DisplayName("Un puerto cerrado es 'no enviado': no hubo conexión, reintentar es seguro")
    void aRefusedConnectionIsNotSent() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }

        PosDelivery delivery = client.notifyOrder(connectionTo(closedPort), payload());

        assertThat(delivery.outcome()).isEqualTo(PosDelivery.Outcome.NOT_SENT);
    }

    @Test
    @DisplayName("Un 2xx es una venta registrada, aunque el cuerpo no se pueda leer")
    void a2xxIsSentEvenWithAGarbageBody() throws IOException {
        startHttpServer(200, "esto no es json");

        PosDelivery delivery = client.notifyOrder(connectionTo(httpServer.getAddress().getPort()), payload());

        assertThat(delivery.outcome()).isEqualTo(PosDelivery.Outcome.SENT);
        assertThat(delivery.response()).isNull();
    }

    @Test
    @DisplayName("Un 2xx con cuerpo legible lo devuelve, para comparar el stock")
    void a2xxReturnsItsBody() throws IOException {
        startHttpServer(201, "{\"data\":{\"results\":[]}}");

        PosDelivery delivery = client.notifyOrder(connectionTo(httpServer.getAddress().getPort()), payload());

        assertThat(delivery.outcome()).isEqualTo(PosDelivery.Outcome.SENT);
        assertThat(delivery.response()).containsKey("data");
    }

    @Test
    @DisplayName("Un 5xx es incierto: el POS pudo haber registrado la venta antes de fallar")
    void a5xxIsUncertain() throws IOException {
        startHttpServer(500, "{}");

        assertThat(client.notifyOrder(connectionTo(httpServer.getAddress().getPort()), payload()).outcome())
                .isEqualTo(PosDelivery.Outcome.UNCERTAIN);
    }

    @Test
    @DisplayName("Un 4xx de contenido es un rechazo: reintentar igual no cambia nada")
    void a4xxIsRejected() throws IOException {
        startHttpServer(422, "{\"error\":\"producto inexistente\"}");

        assertThat(client.notifyOrder(connectionTo(httpServer.getAddress().getPort()), payload()).outcome())
                .isEqualTo(PosDelivery.Outcome.REJECTED);
    }

    @Test
    @DisplayName("Un 429 es 'no enviado': el POS dice explícitamente que no la procesó")
    void a429IsNotSent() throws IOException {
        startHttpServer(429, "{}");

        assertThat(client.notifyOrder(connectionTo(httpServer.getAddress().getPort()), payload()).outcome())
                .isEqualTo(PosDelivery.Outcome.NOT_SENT);
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

    private void startHttpServer(int status, String body) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            // Leer la petición entera antes de responder: cerrar con datos sin leer haría que el sistema
            // operativo resetee la conexión, y el test mediría eso en vez de la respuesta.
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        httpServer.start();
    }

    private static PosConnection connectionTo(int port) {
        PosConnection connection = new PosConnection();
        connection.setApiUrl("http://localhost:" + port);
        connection.setApiKey("k");
        connection.setCompanyId(1L);
        return connection;
    }

    private static PosOrderPayload payload() {
        return new PosOrderPayload("ORD-1:1", Map.of("order_number", "ORD-1"));
    }
}
