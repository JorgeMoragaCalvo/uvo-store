package org.uvo.uvostore.entity.pos;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.uvo.uvostore.entity.payment.EncryptedStringConverter;

/**
 * IMP-08. Una llave de firma de una conexión con el POS (contrato §2).
 *
 * <p>{@link Direction#OUTBOUND} firma lo que la tienda manda al POS; {@link Direction#INBOUND} es con la que el POS
 * firma lo que manda a la tienda. Una rotación deja la anterior viva hasta {@code retiresAt} (7 días); una
 * revocación la mata en el acto. El secreto es base64 y va cifrado: nunca a un log.
 */
@Entity
@Table(name = "pos_connection_keys")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PosConnectionKey {

    public enum Direction { OUTBOUND, INBOUND }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id", nullable = false)
    private PosConnection connection;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direction direction;

    @Column(name = "key_id", nullable = false, length = 100)
    private String keyId;

    @Column(nullable = false)
    @Convert(converter = EncryptedStringConverter.class)
    private String secret;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "retires_at")
    private Instant retiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Si la llave sirve en este instante: no revocada y no retirada. */
    public boolean isUsableAt(Instant now) {
        return revokedAt == null && (retiresAt == null || retiresAt.isAfter(now));
    }

    @Override
    public String toString() {
        return "PosConnectionKey[id=" + id + ", direction=" + direction + ", keyId=" + keyId + ", secret=***]";
    }
}
