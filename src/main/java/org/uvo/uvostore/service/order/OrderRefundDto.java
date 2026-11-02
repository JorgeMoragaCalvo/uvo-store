package org.uvo.uvostore.service.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * G4. Un reembolso, como lo ve el panel. {@code type} distingue lo que este sistema devolvió
 * (FULL/PARTIAL, con referencia de la pasarela) de lo que alguien devolvió por fuera y solo registró
 * aquí (EXTERNAL, sin referencia) — que es precisamente lo que hay que poder mirar cuando la base y
 * la pasarela no cuadran.
 *
 * <p>GAP-02. {@code status} dice si el dinero salió ({@code COMPLETED}), si la pasarela lo rechazó
 * ({@code FAILED}) o si no se sabe ({@code PENDING}, el importe sigue reservado hasta resolverlo).
 */
public record OrderRefundDto(
        Long id,
        BigDecimal amount,
        String type,
        String status,
        String gatewayReference,
        String reason,
        String userName,
        Instant createdAt
) {
}
