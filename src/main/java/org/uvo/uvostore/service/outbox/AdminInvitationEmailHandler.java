package org.uvo.uvostore.service.outbox;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.service.notification.EmailService;

/** IMP-06. La invitación de un administrador (también la del dueño de una tienda nueva y su recuperación de acceso). */
@Component
public class AdminInvitationEmailHandler extends EmailOutboxHandler {

    private final AccessEmailComposer composer;

    public AdminInvitationEmailHandler(AccessEmailComposer composer, EmailService emailService) {
        super(emailService);
        this.composer = composer;
    }

    @Override
    public OutboxType type() {
        return OutboxType.ADMIN_INVITATION_EMAIL;
    }

    @Override
    protected EmailDraft compose(OutboxMessage message) {
        return composer.adminInvitation(message.getUser() == null ? null : message.getUser().getId(),
                message.getTokenHash());
    }
}
