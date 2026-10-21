package org.uvo.uvostore.entity.pos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * POS-3. Un webhook del POS ya atendido, para rechazar su repetición también después de un reinicio. Lo
 * escribe y lo purga {@code PosWebhookReplayGuard}; la entidad existe para el {@code ddl-auto=validate} y para
 * la purga.
 */
@Entity
@Table(name = "pos_webhook_receipts")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PosWebhookReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    // El HMAC ya verificado (64 caracteres hex): es el id del evento (F21).
    @Column(nullable = false, length = 64)
    private String signature;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
