package org.uvo.uvostore.entity.audit.enums;

/** PROD-18. Sobre qué recae el cambio; con {@code target_id} identifica la fila. */
public enum AuditTargetType {
    STORE,
    USER,
    ROLE,
    ORDER,
    PAYMENT_GATEWAY_CONFIG,
    POS_CONNECTION,
    SHIPPING_METHOD,
    SHIPPING_RATE,
    COUPON,
    PRODUCT,
    PRODUCT_VARIATION
}
