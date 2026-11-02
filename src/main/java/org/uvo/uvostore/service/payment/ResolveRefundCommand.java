package org.uvo.uvostore.service.payment;

/**
 * GAP-02. Resolver una intención de reembolso cuyo resultado no se conoce, sin volver a llamar a la pasarela.
 *
 * @param orderId la orden de la intención.
 * @param refundId la intención, que tiene que ser de esa orden y estar {@code PENDING}.
 * @param outcome lo que el operador comprobó en el panel de la pasarela.
 * @param reference la referencia del reembolso en la pasarela. Obligatoria si el dinero salió.
 * @param note por qué se resuelve así. Siempre obligatoria: es lo único que explica una decisión que este
 *        sistema no puede verificar. Queda en el historial de la orden, no en la auditoría.
 */
public record ResolveRefundCommand(Long orderId, Long refundId, RefundResolution outcome, String reference,
                                   String note) {
}
