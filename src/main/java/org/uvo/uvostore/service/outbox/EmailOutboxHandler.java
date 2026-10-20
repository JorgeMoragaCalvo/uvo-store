package org.uvo.uvostore.service.outbox;

import jakarta.mail.SendFailedException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.service.notification.EmailService;

/**
 * PROD-13b. Lo común a los tres correos de una orden: armar el contenido (cada subclase dice cuál), enviarlo
 * y clasificar el resultado.
 *
 * <p>Un correo que no se sabe si salió se reintenta: un duplicado es una molestia, no un documento
 * tributario. Lo que no se reintenta es lo que no va a cambiar: una dirección que el servidor rechaza o un
 * mensaje mal formado.
 */
abstract class EmailOutboxHandler implements OutboxHandler {

    /** Lo que queda en {@code last_error} de lo omitido por falta de SMTP; el SQL de V25 lo busca por esto. */
    static final String SMTP_NOT_CONFIGURED = "smtp-not-configured";

    protected final OrderEmailComposer composer;
    private final EmailService emailService;

    protected EmailOutboxHandler(OrderEmailComposer composer, EmailService emailService) {
        this.composer = composer;
        this.emailService = emailService;
    }

    protected abstract EmailDraft compose(Long orderId);

    @Override
    public OutboxResult handle(OutboxMessage message) {
        EmailDraft draft;
        try {
            draft = compose(message.getOrder().getId());
        } catch (RuntimeException e) {
            // Falló antes de enviar nada.
            return OutboxResult.retryable("No se pudo preparar el correo: " + describe(e));
        }
        if (draft.skipped()) {
            return OutboxResult.skipped(draft.skipReason());
        }

        try {
            EmailService.Delivery delivery = emailService.deliver(draft.to(), draft.subject(), draft.body());
            return delivery == EmailService.Delivery.NOT_CONFIGURED
                    ? OutboxResult.skipped(SMTP_NOT_CONFIGURED)
                    : OutboxResult.delivered(null);
        } catch (MailParseException | MailPreparationException e) {
            return OutboxResult.permanent("Correo mal formado: " + describe(e));
        } catch (MailSendException e) {
            return rejectedAddress(e)
                    ? OutboxResult.permanent("El servidor rechazó la dirección: " + describe(e))
                    : OutboxResult.retryable("Fallo de envío: " + describe(e));
        } catch (MailException e) {
            // Autenticación, conexión: no depende del mensaje, puede ir mejor más tarde.
            return OutboxResult.retryable("Fallo de envío: " + describe(e));
        }
    }

    private static boolean rejectedAddress(MailSendException e) {
        for (Exception failure : e.getMessageExceptions()) {
            for (Throwable current = failure; current != null; current = current.getCause()) {
                if (current instanceof SendFailedException) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
