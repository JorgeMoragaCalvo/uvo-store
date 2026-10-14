package org.uvo.uvostore.service.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * PROD-17a. El cuerpo de un correo no se registra, venga como venga el envío.
 *
 * <p>Es donde viajan los tokens de invitación y de restablecimiento (en el enlace del cuerpo), y por eso
 * este servicio es el sitio donde sería más fácil regresar: lo natural cuando no hay SMTP en desarrollo es
 * imprimir el cuerpo "para poder abrir el enlace". La búsqueda de PROD-17a encontró que hoy solo se
 * registran destinatario y asunto; este test fija que siga siendo así en las tres salidas del método.
 */
class EmailServiceImplLoggingTest {

    private static final String TOKEN = "tok-de-invitacion-0123456789abcdef";
    private static final String BODY = "Acepta la invitación:\n"
            + "https://demo.uvostore.cl/admin/aceptar-invitacion?token=" + TOKEN + "\n";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void captureLogs() {
        serviceLogger = (Logger) LoggerFactory.getLogger(EmailServiceImpl.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        serviceLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("Sin SMTP configurado se registra el destinatario y el asunto, no el cuerpo")
    void withoutSmtpTheBodyIsNotLogged() {
        new EmailServiceImpl(mailSender, "", "no-reply@uvostore.cl").send("ana@test.local", "Invitación", BODY);

        assertOnlyMetadataWasLogged();
    }

    @Test
    @DisplayName("Un envío correcto registra el destinatario y el asunto, no el cuerpo")
    void aSentMessageDoesNotLogTheBody() {
        new EmailServiceImpl(mailSender, "smtp.test.local", "no-reply@uvostore.cl")
                .send("ana@test.local", "Invitación", BODY);

        assertOnlyMetadataWasLogged();
    }

    @Test
    @DisplayName("Un envío fallido registra el error, no el cuerpo")
    void aFailedSendDoesNotLogTheBody() {
        doThrow(new MailSendException("SMTP caído")).when(mailSender).send(any(SimpleMailMessage.class));

        new EmailServiceImpl(mailSender, "smtp.test.local", "no-reply@uvostore.cl")
                .send("ana@test.local", "Invitación", BODY);

        assertOnlyMetadataWasLogged();
    }

    private void assertOnlyMetadataWasLogged() {
        // Control: sin ninguna línea, las afirmaciones de abajo pasarían aunque el servicio callara todo.
        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage())
                    .contains("ana@test.local")
                    .doesNotContain(TOKEN)
                    .doesNotContain("aceptar-invitacion");
            assertThat(event.getThrowableProxy()).isNull();
        }
    }
}
