package org.uvo.uvostore.entity.order.enums;

// What the customer picked at checkout. Distinct from PaymentGatewayType (entity/payment/enums)
// which only covers real integrations needing credentials — MANUAL ("pago contra entrega" /
// bank transfer, confirmed by an admin) has no gateway behind it.
public enum PaymentMethodType {
    MANUAL,
    STRIPE,
    WEBPAY,
    MERCADOPAGO
}
