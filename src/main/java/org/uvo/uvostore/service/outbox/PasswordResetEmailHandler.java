package org.uvo.uvostore.service.outbox;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.service.notification.EmailService;

/** IMP-06. El enlace de «¿Olvidaste tu contraseña?» del panel. */
@Component
public class PasswordResetEmailHandler extends EmailOutboxHandler {

    private final AccessEmailComposer composer;

    public PasswordResetEmailHandler(AccessEmailComposer composer, EmailService emailService) {
        super(emailService);
        this.composer = composer;
    }

    @Override
    public OutboxType type() {
        return OutboxType.PASSWORD_RESET_EMAIL;
    }

    @Override
    protected EmailDraft compose(OutboxMessage message) {
        return composer.passwordReset(message.getUser() == null ? null : message.getUser().getId(),
                message.getTokenHash());
    }
}
