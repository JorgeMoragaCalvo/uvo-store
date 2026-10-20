package org.uvo.uvostore.service.outbox;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.service.notification.EmailService;

/** PROD-13b. El correo de compra confirmada; ocupa el lugar de {@code OrderConfirmationEmailListener}. */
@Component
public class OrderConfirmationEmailHandler extends EmailOutboxHandler {

    public OrderConfirmationEmailHandler(OrderEmailComposer composer, EmailService emailService) {
        super(composer, emailService);
    }

    @Override
    public OutboxType type() {
        return OutboxType.ORDER_CONFIRMATION_EMAIL;
    }

    @Override
    protected EmailDraft compose(Long orderId) {
        return composer.orderConfirmation(orderId);
    }
}
