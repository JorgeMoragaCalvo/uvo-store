package org.uvo.uvostore.entity.order;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.uvo.uvostore.entity.order.enums.RefundType;
import org.uvo.uvostore.entity.security.User;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * G4. Una devolución de dinero sobre una orden. Hay 1..N por orden porque los reembolsos parciales
 * se acumulan: lo devuelto se suma sobre estas filas en vez de guardarse en una columna de
 * {@code orders}, que podría quedar desfasada respecto de lo que realmente se hizo.
 */
@Entity
@Table(name = "order_refunds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class OrderRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Order order;

    @Column(nullable = false)
    private BigDecimal amount;

    /**
     * Lo que devolvió la pasarela: el id del refund en Stripe o MercadoPago, o el tipo de Transbank
     * ({@code REVERSED} para la anulación del mismo día, {@code NULLIFIED} para el reembolso). Nulo
     * en los {@link RefundType#EXTERNAL}, que por definición no pasaron por aquí.
     */
    @Column(name = "gateway_reference")
    private String gatewayReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundType type;

    @Column(columnDefinition = "text")
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
