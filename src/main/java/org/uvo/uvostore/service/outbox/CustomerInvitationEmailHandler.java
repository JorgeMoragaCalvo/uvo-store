package org.uvo.uvostore.service.outbox;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.service.notification.EmailService;

/**
 * PROD-13b. La invitación a crear cuenta de un comprador invitado (F24); ocupa el lugar de
 * {@code CustomerInvitationEmailListener}.
 */
@Component
public class CustomerInvitationEmailHandler extends EmailOutboxHandler {

    public CustomerInvitationEmailHandler(OrderEmailComposer composer, EmailService emailService) {
        super(composer, emailService);
    }

    @Override
    public OutboxType type() {
        return OutboxType.CUSTOMER_INVITATION_EMAIL;
    }

    @Override
    protected EmailDraft compose(Long orderId) {
        return composer.customerInvitation(orderId);
    }
}
