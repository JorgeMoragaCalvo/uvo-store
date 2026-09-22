package org.uvo.uvostore.pos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosOrderPayload;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R1. El fallo que esto reproduce no es "el POS devuelve un error": es "el POS acepta la conexión y
 * no contesta nunca". No lanza nada, no aparece en ningún log, y con el read timeout de 120s que
 * había aquí —y la llamada dentro del hilo de la petición— cada checkout se quedaba dos minutos
 * esperando por cada empresa a notificar.
 *
 * <p>Un {@code ServerSocket} que acepta y no escribe es exactamente ese servidor, y no hace falta
 * añadir ninguna dependencia para tenerlo.
 */
class PosClientTimeoutTest {

    private static final int READ_TIMEOUT_MS = 800;

    private ServerSocket deafServer;
    private Thread acceptLoop;
    private final List<Socket> accepted = new ArrayList<>();

    @BeforeEach
    void startDeafServer() throws IOException {
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

    @AfterEach
    void stopDeafServer() throws IOException {
        for (Socket socket : accepted) {
            socket.close();
        }
        deafServer.close();
        acceptLoop.interrupt();
    }

    @Test
    @DisplayName("Un POS que acepta y no contesta corta en el tope configurado, no espera para siempre")
    void aDeafPosIsCutOffAtTheConfiguredTimeout() {
        PosClient client = new PosClient(500, READ_TIMEOUT_MS);
        PosConnection connection = new PosConnection();
        connection.setApiUrl("http://localhost:" + deafServer.getLocalPort());
        connection.setApiKey("k");
        connection.setCompanyId(1L);

        Instant start = Instant.now();
        assertThatThrownBy(() -> client.notifyOrder(connection, payload()))
                .as("tiene que fallar por timeout, no quedarse colgado")
                .isInstanceOf(Exception.class);
        Duration elapsed = Duration.between(start, Instant.now());

        // El margen es ancho a propósito (la máquina puede ir lenta); lo que este test tiene que
        // distinguir es "cortó" de "no cortó". Con el valor anterior fijo en el código —120s— esto
        // no terminaba.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
    }

    private PosOrderPayload payload() {
        return new PosOrderPayload("ORD-1:1", Map.of("order_number", "ORD-1"));
    }
}
