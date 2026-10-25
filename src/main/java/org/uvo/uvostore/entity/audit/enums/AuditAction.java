package org.uvo.uvostore.entity.audit.enums;

/**
 * PROD-18. Lo que se audita. Cada constante nueva necesita su migración: {@code audit_events_action_valid} lista
 * los valores (B6), y {@code AuditCatalogConsistencyTest} falla si las dos listas se separan.
 */
public enum AuditAction {
    // Reembolsos: la intención (antes de llamar a la pasarela), el resultado y el registrado a mano.
    REFUND_REQUEST,
    REFUND_ISSUE,
    REFUND_RECORD_EXTERNAL,

    // Órdenes desde el panel.
    ORDER_CANCEL,
    ORDER_PAYMENT_STATUS_UPDATE,

    // Dinero y credenciales.
    PAYMENT_GATEWAY_UPDATE,
    SETTINGS_MONEY_UPDATE,
    SETTINGS_STRIPE_UPDATE,
    POS_CONNECTION_UPDATE,
    SHIPPING_METHOD_CREDENTIALS_UPDATE,

    // Administradores y su acceso.
    USER_CREATE,
    USER_UPDATE,
    USER_ACTIVATE,
    USER_DEACTIVATE,
    USER_DELETE,
    USER_INVITATION_RESEND,
    USER_INVITATION_ACCEPT,
    USER_PASSWORD_RESET,
    AUTH_LOGIN,

    ROLE_CREATE,
    ROLE_UPDATE,
    ROLE_DELETE,

    // Plataforma. Desde PROD-23 (V29) también suspender o reactivar, cerrar y recuperar el acceso del dueño.
    STORE_CREATE,
    STORE_DOMAIN_UPDATE,
    STORE_DOMAIN_VERIFY,
    STORE_STATUS_UPDATE,
    STORE_CLOSE,
    STORE_OWNER_ACCESS_RECOVERY,

    // Lo que cambia cuánto se cobra.
    COUPON_CREATE,
    COUPON_UPDATE,
    COUPON_DELETE,
    COUPON_STATUS_TOGGLE,
    SHIPPING_RATE_CREATE,
    SHIPPING_RATE_UPDATE,
    SHIPPING_RATE_DELETE,
    SHIPPING_RATE_STATUS_TOGGLE,
    SHIPPING_METHOD_CREATE,
    SHIPPING_METHOD_UPDATE,
    SHIPPING_METHOD_DELETE,
    SHIPPING_METHOD_STATUS_TOGGLE,
    PRODUCT_PRICE_UPDATE,
    VARIATION_PRICE_UPDATE
}
