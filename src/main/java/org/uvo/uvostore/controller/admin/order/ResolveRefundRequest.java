package org.uvo.uvostore.controller.admin.order;

import jakarta.validation.constraints.NotNull;
import org.uvo.uvostore.service.payment.RefundResolution;

/**
 * GAP-02. Cómo terminó, según el panel de la pasarela, un reembolso que quedó {@code PENDING}. Que la nota sea
 * obligatoria, y la referencia cuando el dinero salió, lo comprueba {@code RefundService}, como el motivo del
 * reembolso externo.
 */
public record ResolveRefundRequest(@NotNull RefundResolution outcome, String reference, String note) {
}
