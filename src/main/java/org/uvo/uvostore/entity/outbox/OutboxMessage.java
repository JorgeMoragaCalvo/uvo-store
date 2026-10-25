package org.uvo.uvostore.entity.outbox;

import jakarta.persistence.Column;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;

/**
 * PROD-13b. Un efecto posterior al commit de una orden, escrito en la misma transacción que lo origina.
 * Ver {@code V25__outbox.sql} para el porqué y {@code OutboxDispatcher} para cómo se entrega.
 *
 * <p>Los cambios de estado no pasan por esta entidad sino por los UPDATE condicionados de
 * {@code OutboxMessageRepository}: el estado leído puede estar viejo, y lo que decide es la fila.
 */
@Entity
@Table(name = "outbox_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxType type;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "dedup_key", nullable = false, unique = true)
    private String dedupKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    /**
     * PROD-15b. El id de la petición (o de la pasada de job) que encoló la fila. Se pone en el MDC al entregarla y
     * {@code PosClient} lo manda al POS como {@code X-Request-Id}. Null en las filas anteriores a V30.
     */
    @Column(name = "request_id", length = 64)
    private String requestId;
}
