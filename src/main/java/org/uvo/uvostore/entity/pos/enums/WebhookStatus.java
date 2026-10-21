package org.uvo.uvostore.entity.pos.enums;

public enum WebhookStatus {
    RECEIVED,
    PROCESSING,
    SUCCESS,
    FAILED,
    // POS-3. La entrada era vieja o repetida y no se aplicó a propósito. No es un fallo: no va a Sentry.
    DISCARDED
}
