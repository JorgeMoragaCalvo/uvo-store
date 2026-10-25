package org.uvo.uvostore.service.payment;

import java.math.BigDecimal;

/**
 * G4. Lo que hace falta para devolver dinero.
 *
 * @param orderId la orden a reembolsar.
 * @param amount cuánto devolver. <b>Null significa "todo lo que quede"</b>, que es el caso normal:
 *        así el llamador no tiene que calcular el saldo —ni acertar— para un reembolso total.
 * @param reason por qué. Obligatorio en el reembolso externo, donde es lo único que explica por qué
 *        la base dice una cosa distinta de lo que este sistema hizo.
 * @param userId quién lo pidió. Se guarda con el reembolso porque devolver dinero es justo lo que
 *        hay que poder atribuir. Desde PROD-18 también queda en {@code audit_events} (el actor lo toma
 *        {@code AuditTrail} del contexto de seguridad); la columna de {@code order_refunds} sigue siendo
 *        la que dice de quién es cada fila del reembolso.
 */
public record RefundCommand(Long orderId, BigDecimal amount, String reason, Long userId) {
}
