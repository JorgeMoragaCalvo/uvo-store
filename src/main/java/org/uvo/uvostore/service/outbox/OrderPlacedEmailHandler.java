package org.uvo.uvostore.service.outbox;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.service.notification.EmailService;

/** PROD-13b. El acuse de recibo del pedido; ocupa el lugar de {@code OrderPlacedEmailListener}. */
@Component
public class OrderPlacedEmailHandler extends EmailOutboxHandler {

    private final OrderEmailComposer composer;

    public OrderPlacedEmailHandler(OrderEmailComposer composer, EmailService emailService) {
        super(emailService);
        this.composer = composer;
    }

    @Override
    public OutboxType type() {
        return OutboxType.ORDER_PLACED_EMAIL;
    }

    @Override
    protected EmailDraft compose(OutboxMessage message) {
        return composer.orderPlaced(message.getOrder().getId());
    }
}
