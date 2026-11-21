package org.uvo.uvostore.service.payment;

import java.math.BigDecimal;

/**
 * IMP-09. Lo que la pasarela dice de un pago, leído sin tocar nada: ni {@code commit}, ni {@code markPaid}, ni un
 * reembolso. Es lo que el informe de recuperación compara con lo que dice la base restaurada.
 *
 * @param providerStatus el estado tal como lo da la pasarela ({@code AUTHORIZED}, {@code approved}, {@code paid}…)
 * @param charged        si la pasarela capturó el dinero en algún momento (aunque después lo haya devuelto)
 * @param amount         lo que capturó, en pesos; nulo si no capturó o no lo informa
 * @param refunded       lo que devolvió, en pesos; nulo si la pasarela no lo informa por esta vía
 * @param reference      el identificador de la operación en la pasarela (token, id de pago, sesión)
 */
public record ProviderPaymentView(String providerStatus, boolean charged, BigDecimal amount, BigDecimal refunded,
                                  String reference) {
}
