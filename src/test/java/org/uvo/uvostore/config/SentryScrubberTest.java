package org.uvo.uvostore.config;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** IMP-03. Ningún token ni credencial concatenado en un texto llega a Sentry. */
class SentryScrubberTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "GET /admin/reset-password?token=abc123&lang=es | GET /admin/reset-password?token=[Filtered]&lang=es",
            "password=hunter2 rechazada                       | password=[Filtered] rechazada",
            "apiKey: sk_live_123                              | apiKey: [Filtered]",
            "api_key=\"sk_live_123\"                           | api_key=\"[Filtered]\"",
            "Authorization: Bearer eyJhbGciOi.payload.firma    | Authorization: Bearer [Filtered]",
            "webpay token_ws=01ab, monto 1000                  | webpay token_ws=[Filtered], monto 1000",
            "client_secret: pi_3_secret_x}                     | client_secret: [Filtered]}",
    })
    @DisplayName("Conserva el nombre y sustituye el valor")
    void scrubsValuesByName(String input, String expected) {
        assertThat(SentryScrubber.scrub(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Un texto sin datos sensibles queda intacto")
    void leavesOrdinaryTextAlone() {
        String text = "No se pudo descontar el stock de la orden ORD-123: variante 45 sin unidades";
        assertThat(SentryScrubber.scrub(text)).isEqualTo(text);
        assertThat(SentryScrubber.scrub((String) null)).isNull();
        assertThat(SentryScrubber.scrub("")).isEmpty();
    }

    @Test
    @DisplayName("Limpia el mensaje, las excepciones (también la causa), los extra y los breadcrumbs")
    void scrubsEveryTextFieldOfTheEvent() {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setMessage("fallo con token=uno");
        message.setFormatted("fallo con token=uno");
        event.setMessage(message);
        SentryException outer = new SentryException();
        outer.setValue("Error HTTP: password=dos");
        SentryException cause = new SentryException();
        cause.setValue("causa: secret=tres");
        event.setExceptions(List.of(outer, cause));
        event.setExtra("detalle", "signature=cuatro");
        event.setExtra("intentos", 3);
        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setMessage("llamada con Bearer cinco");
        event.setBreadcrumbs(List.of(breadcrumb));

        SentryEvent scrubbed = SentryScrubber.scrub(event);

        assertThat(scrubbed.getMessage().getMessage()).isEqualTo("fallo con token=[Filtered]");
        assertThat(scrubbed.getMessage().getFormatted()).isEqualTo("fallo con token=[Filtered]");
        assertThat(scrubbed.getExceptions()).extracting(SentryException::getValue)
                .containsExactly("Error HTTP: password=[Filtered]", "causa: secret=[Filtered]");
        assertThat(scrubbed.getExtra("detalle")).isEqualTo("signature=[Filtered]");
        assertThat(scrubbed.getExtra("intentos")).isEqualTo(3);
        assertThat(scrubbed.getBreadcrumbs()).singleElement()
                .extracting(Breadcrumb::getMessage).isEqualTo("llamada con Bearer [Filtered]");
    }
}
