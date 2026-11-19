package org.uvo.uvostore.entity.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.uvo.uvostore.entity.payment.enums.BankAccountType;
import org.uvo.uvostore.entity.tenant.Store;

import java.time.Instant;

/**
 * IMP-07 (V37). El pago manual de una tienda: una transferencia bancaria con los datos que el comprador necesita y el
 * plazo para pagar. Sin fila, o con {@code enabled = false}, la tienda no ofrece pago manual.
 *
 * <p>No son secretos: la tienda se los muestra a cada comprador. Por eso van en claro, a diferencia de las
 * credenciales de las pasarelas.
 */
@Entity
@Table(name = "bank_transfer_settings")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BankTransferSettings {

    /** El plazo si la tienda no elige otro. */
    public static final int DEFAULT_PAYMENT_WINDOW_HOURS = 48;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false, unique = true)
    private Store store;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "holder_name", length = 120)
    private String holderName;

    /** Normalizado por {@code ChileanRut}: sin puntos y con guion. */
    @Column(name = "holder_rut", length = 12)
    private String holderRut;

    @Column(name = "bank_name", length = 80)
    private String bankName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 20)
    private BankAccountType accountType;

    @Column(name = "account_number", length = 30)
    private String accountNumber;

    /** Adónde manda el comprador el comprobante. */
    @Column(name = "notice_email")
    private String noticeEmail;

    @Column(columnDefinition = "text")
    private String instructions;

    @Column(name = "payment_window_hours", nullable = false)
    private int paymentWindowHours = DEFAULT_PAYMENT_WINDOW_HOURS;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
