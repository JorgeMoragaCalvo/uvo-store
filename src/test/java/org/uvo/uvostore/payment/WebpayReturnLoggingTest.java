package org.uvo.uvostore.payment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.uvo.uvostore.controller.order.WebpayController;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROD-17a. Lo que {@code /api/v1/webpay/return} deja en el log cuando falla la confirmación.
 *
 * <p>Antes escribía el {@code token_ws} completo. Es la credencial de esa transacción frente a Transbank y
 * el endpoint es público, así que cada línea de log era una copia del token en el agregador. Y como el valor
 * llega sin autenticar, imprimirlo tal cual dejaba además que cualquiera fabricara líneas de log (saltos de
 * línea en el parámetro) o las inflara.
 *
 * <p>El camino que se recorre es el real: un token que no corresponde a ninguna orden hace fallar
 * {@code commitTransaction} antes de llegar a Transbank, y el controlador lo registra con el logger de
 * verdad. No se simula nada, porque lo que se afirma es lo que sale por ese logger.
 */
class WebpayReturnLoggingTest extends IntegrationTestSupport {

    // 64 hexadecimales, como un token real de Webpay. Lo que no puede salir son los caracteres 9 a 64: el
    // prefijo de 8 sí sale a propósito, así que afirmar solo sobre el valor entero dejaría pasar un log que
    // imprimiera casi todo el token.
    private static final String REAL_LOOKING_TOKEN =
            "01ab3f9c5d7e21a4b8c06f93e1d2a7b4c8e0f1a3b5d7c9e1f3a5b7c9d1e3f5a7";
    private static final String TOKEN_TAIL = REAL_LOOKING_TOKEN.substring(8);

    private ListAppender<ILoggingEvent> logs;
    private Logger controllerLogger;

    @BeforeEach
    void captureLogs() {
        controllerLogger = (Logger) LoggerFactory.getLogger(WebpayController.class);
        logs = new ListAppender<>();
        logs.start();
        controllerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        controllerLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("Una confirmación fallida deja el prefijo del token y la tienda, nunca el token entero")
    void aFailedCommitNeverLogsTheWholeToken() throws Exception {
        Store store = createStore("wp-log");

        mockMvc.perform(post("/api/v1/webpay/return")
                        .header("Host", hostHeader(store))
                        .param("token_ws", REAL_LOOKING_TOKEN))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://" + hostHeader(store) + "/checkout?error=webpay"));

        List<ILoggingEvent> warnings = warnings();
        // Control: si no se registrara nada, las afirmaciones de abajo pasarían igual y no probarían nada.
        assertThat(warnings).hasSize(1);

        String line = warnings.get(0).getFormattedMessage();
        assertThat(line).contains("Error confirmando transacción Webpay");
        assertThat(line).contains("store_id=" + store.getId());
        assertThat(line).contains("token_prefix=01ab3f9c...");
        assertThat(line).doesNotContain(TOKEN_TAIL);
        assertThat(line).doesNotContain(REAL_LOOKING_TOKEN);
    }

    @Test
    @DisplayName("Un token_ws hostil no puede fabricar líneas de log ni inflarlas")
    void aHostileTokenCannotForgeOrFloodTheLog() throws Exception {
        Store store = createStore("wp-log-evil");
        String forged = "ab\n2026-10-13T00:00:00.000Z ERROR Webpay - pago aprobado orden ORD-FALSA\r\n"
                + "x".repeat(20_000);

        mockMvc.perform(post("/api/v1/webpay/return")
                        .header("Host", hostHeader(store))
                        .param("token_ws", forged))
                .andExpect(status().isFound());

        List<ILoggingEvent> warnings = warnings();
        assertThat(warnings).hasSize(1);

        String line = warnings.get(0).getFormattedMessage();
        assertThat(line).doesNotContain("\n").doesNotContain("\r");
        assertThat(line).doesNotContain("ORD-FALSA");
        // El recorte va antes del saneo: la línea no crece con el tamaño de lo que mandó quien llama.
        assertThat(line.length()).isLessThan(300);
        // Los 8 primeros caracteres son "ab\n2026-": el salto y el guion salen como '?'.
        assertThat(line).contains("token_prefix=ab?2026?...");
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }
}
