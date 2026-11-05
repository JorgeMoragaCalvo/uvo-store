package org.uvo.uvostore.service.outbox;

import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;

import java.time.Instant;

/**
 * GAP-04. Una entrega del outbox que falló de forma definitiva, como la ve el panel («Envíos fallidos»).
 *
 * @param uncertain una venta al POS que pudo haber llegado: reintentarla sin revisar el POS puede emitir un segundo
 *                  documento tributario, así que el panel pide confirmarlo.
 * @param legacy    una fila heredada de V25, sin empresa: no se puede reintentar, solo marcar resuelta.
 */
public record OutboxFailureDto(Long id, OutboxType type, OutboxChannel channel, Long orderId, String orderNumber,
                               Long companyId, int attempts, String lastError, Instant createdAt, Instant updatedAt,
                               boolean uncertain, boolean legacy) {
}
