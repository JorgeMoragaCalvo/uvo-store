package org.uvo.uvostore.service.platform;

/**
 * PROD-23. Por qué se suspende o se cierra una tienda. Una lista cerrada y no texto libre: va a la auditoría
 * ({@code details.reason}), que nunca guarda texto libre (PROD-18). No se guarda en la tienda.
 */
public enum StoreStatusReason {
    /** La empresa dio de baja el módulo de e-commerce en UvoPOS. */
    MODULE_DEACTIVATED,
    NON_PAYMENT,
    MERCHANT_REQUEST,
    SECURITY,
    OTHER
}
