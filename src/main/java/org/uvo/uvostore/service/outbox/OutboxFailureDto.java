package org.uvo.uvostore.service.outbox;

import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;

import java.time.Instant;

/**
 * GAP-04. Una entrega del outbox que falló de forma definitiva, como la ve el panel («Envíos fallidos»). IMP-06:
 * también un correo de acceso, que no tiene orden sino usuario; y como lo ve la API de plataforma, con su tienda.
 *
 * @param orderId        null en un correo de acceso.
 * @param orderNumber    null en un correo de acceso.
 * @param userId         solo en un correo de acceso: el administrador al que va.
 * @param recipientEmail solo en un correo de acceso: su dirección actual (nunca el token).
 * @param uncertain      una venta al POS que pudo haber llegado: reintentarla sin revisar el POS puede emitir un
 *                       segundo documento tributario, así que el panel pide confirmarlo.
 * @param legacy         una fila heredada de V25, sin empresa: no se puede reintentar, solo marcar resuelta.
 */
public record OutboxFailureDto(Long id, OutboxType type, OutboxChannel channel, Long storeId, Long orderId,
                               String orderNumber, Long companyId, Long userId, String recipientEmail, int attempts,
                               String lastError, Instant createdAt, Instant updatedAt, boolean uncertain,
                               boolean legacy) {
}
