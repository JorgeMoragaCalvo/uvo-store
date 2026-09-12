package org.uvo.uvostore.controller.admin.order;

import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * G4. {@code amount} nulo significa "todo lo que quede por devolver", que es el reembolso total y el
 * caso normal; el panel no tiene que calcular el saldo ni acertar con él. El motivo es opcional aquí
 * y obligatorio en el reembolso externo, donde es lo único que explica un movimiento que este sistema
 * no puede verificar — esa comprobación vive en {@code RefundService}, no en la validación del DTO.
 */
public record RefundRequest(@Positive BigDecimal amount, String reason) {
}
