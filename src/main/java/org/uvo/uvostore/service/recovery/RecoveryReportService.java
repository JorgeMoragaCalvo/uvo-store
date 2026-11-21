package org.uvo.uvostore.service.recovery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.config.RecoveryModePolicy;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderRefund;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.order.enums.RefundStatus;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.order.PaymentService;
import org.uvo.uvostore.service.payment.MercadoPagoService;
import org.uvo.uvostore.service.payment.ProviderPaymentView;
import org.uvo.uvostore.service.payment.WebpayService;
import org.uvo.uvostore.service.recovery.RecoveryReport.OutboxCheck;
import org.uvo.uvostore.service.recovery.RecoveryReport.OutboxFinding;
import org.uvo.uvostore.service.recovery.RecoveryReport.PaymentCheck;
import org.uvo.uvostore.service.recovery.RecoveryReport.PaymentFinding;
import org.uvo.uvostore.service.recovery.RecoveryReport.RefundCheck;
import org.uvo.uvostore.service.recovery.RecoveryReport.RefundFinding;
import org.uvo.uvostore.service.recovery.RecoveryReport.StoreSection;

/**
 * IMP-09. Compara la base restaurada con las pasarelas antes de reanudar nada (§11.2: «un backup antiguo puede contener
 * operaciones que el proveedor ya procesó»).
 *
 * <p><b>Solo lee.</b> A las pasarelas les pregunta el estado ({@code inspect}: Webpay {@code status()}, la búsqueda de
 * MercadoPago, la sesión de Stripe), nunca confirma, cobra ni devuelve; y en la base no escribe nada. Corregir cada
 * diferencia es una decisión de una persona, que queda en el acta: confirmar un pago, resolver un reembolso incierto,
 * reintentar o cerrar una fila del outbox. Lo único que escribe este servicio es {@link #holdRestoredOutbox}, y solo en
 * el outbox.
 *
 * <p>Exige el modo recuperación: fuera de él, la conciliación de pagos y el outbox corren solos, y un informe tomado a
 * mitad de su trabajo describiría algo que ya cambió.
 *
 * <p>La venta al POS no se puede comparar todavía: el POS no ofrece la consulta por clave del contrato de IMP-08
 * ({@code GET /orders/external/{clave}}, §9). Hasta entonces, toda venta sin terminar sale como
 * {@link OutboxCheck#INCIERTO_POS} y se revisa en el POS.
 */
@Service
public class RecoveryReportService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryReportService.class);

    /** Topes por lista. Una restauración real no llega; si llega, el informe lo dice y hay que acotar {@code from}. */
    static final int MAX_ORDERS = 1_000;
    static final int MAX_REFUNDS = 1_000;
    static final int MAX_OUTBOX = 2_000;

    static final String HOLD_REASON = "Restaurado desde respaldo (IMP-09): pudo haberse entregado después del punto de "
            + "restauración. Comprobar en el destino y reintentar solo si no llegó; si llegó, cerrar.";

    private static final int MAX_DETAIL = 300;

    private final RecoveryModePolicy recoveryMode;
    private final OrderRepository orderRepository;
    private final OrderRefundRepository refundRepository;
    private final OutboxMessageRepository outboxRepository;
    private final PaymentService stripe;
    private final WebpayService webpay;
    private final MercadoPagoService mercadoPago;
    private final CredentialDecryptProbe credentialProbe;

    public RecoveryReportService(RecoveryModePolicy recoveryMode, OrderRepository orderRepository,
                                 OrderRefundRepository refundRepository, OutboxMessageRepository outboxRepository,
                                 PaymentService stripe, WebpayService webpay, MercadoPagoService mercadoPago,
                                 CredentialDecryptProbe credentialProbe) {
        this.recoveryMode = recoveryMode;
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.outboxRepository = outboxRepository;
        this.stripe = stripe;
        this.webpay = webpay;
        this.mercadoPago = mercadoPago;
        this.credentialProbe = credentialProbe;
    }

    public RecoveryReport report(Instant from) {
        requireRecoveryMode();
        List<Order> orders = orderRepository.findForRecoveryReport(from, PageRequest.of(0, MAX_ORDERS));
        List<OrderRefund> refunds = refundRepository.findForRecoveryReport(from, PageRequest.of(0, MAX_REFUNDS));
        List<OutboxMessage> outbox = outboxRepository.findUnfinishedForRecovery(PageRequest.of(0, MAX_OUTBOX));
        boolean truncated = orders.size() == MAX_ORDERS || refunds.size() == MAX_REFUNDS || outbox.size() == MAX_OUTBOX;

        // Una consulta a la pasarela por orden, compartida por el hallazgo de pago y los de sus reembolsos.
        Map<Long, Lookup> lookups = new HashMap<>();
        Map<Long, Section> sections = new TreeMap<>();

        for (Order order : orders) {
            Lookup lookup = lookups.computeIfAbsent(order.getId(), id -> lookup(order));
            section(sections, order.getStore()).payments.add(paymentFinding(order, lookup));
        }
        for (OrderRefund refund : refunds) {
            Order order = refund.getOrder();
            Lookup lookup = refund.getStatus() == RefundStatus.PENDING
                    ? lookups.computeIfAbsent(order.getId(), id -> lookup(order))
                    : null;
            section(sections, order.getStore()).refunds.add(refundFinding(refund, lookup));
        }
        for (OutboxMessage message : outbox) {
            section(sections, message.getStore()).outbox.add(outboxFinding(message));
        }

        Map<String, Long> attention = new LinkedHashMap<>();
        List<StoreSection> stores = new ArrayList<>();
        for (Section section : sections.values()) {
            section.payments.stream().filter(f -> f.classification() != PaymentCheck.COINCIDE)
                    .forEach(f -> attention.merge("pago." + f.classification(), 1L, Long::sum));
            section.refunds.stream().filter(f -> f.classification() != RefundCheck.REGISTRADO)
                    .forEach(f -> attention.merge("reembolso." + f.classification(), 1L, Long::sum));
            section.outbox.forEach(f -> attention.merge("outbox." + f.classification(), 1L, Long::sum));
            stores.add(new StoreSection(section.store.getId(), section.store.getSlug(), section.payments,
                    section.refunds, section.outbox));
        }
        CredentialDecryptProbe.Result credentials = credentialProbe.probe();
        if (credentials.failures() > 0) {
            attention.put("credenciales.sin_descifrar", (long) credentials.failures());
        }

        log.info("Informe de recuperación desde {}: {} órdenes, {} reembolsos, {} filas de outbox; hallazgos {}",
                from, orders.size(), refunds.size(), outbox.size(), attention);
        return new RecoveryReport(Instant.now(), from, true, truncated, credentials, attention, stores);
    }

    /**
     * Retiene lo que el respaldo traía en cola o en vuelo (ver {@link OutboxMessageRepository#holdRestored}).
     *
     * @return cuántas filas quedaron retenidas
     */
    @Transactional
    public int holdRestoredOutbox(Instant restoredAt) {
        requireRecoveryMode();
        int held = outboxRepository.holdRestored(restoredAt, HOLD_REASON, Instant.now());
        log.warn("Modo recuperación: {} filas del outbox anteriores a {} retenidas como fallidas para revisión", held,
                restoredAt);
        return held;
    }

    private void requireRecoveryMode() {
        if (!recoveryMode.isActive()) {
            throw new BusinessException("El informe de recuperación solo existe en modo recuperación "
                    + "(APP_RECOVERY_MODE=true): fuera de él, la conciliación y el outbox cambian lo que el informe describe.");
        }
    }

    // ---------------------------------------------------------------- pagos

    /** Lo que dijo la pasarela de una orden: su vista, o el motivo por el que no se pudo preguntar. */
    private record Lookup(Optional<ProviderPaymentView> view, String error) {
    }

    private Lookup lookup(Order order) {
        if (order.getPaymentMethod() == null || order.getPaymentMethod() == PaymentMethodType.MANUAL) {
            return new Lookup(Optional.empty(), "Pago manual: no hay pasarela a la que preguntar");
        }
        AtomicReference<Optional<ProviderPaymentView>> view = new AtomicReference<>(Optional.empty());
        try {
            // Con las credenciales de la tienda de la orden, como la conciliación (TenantContext.runWithin).
            TenantContext.runWithin(order.getStore(), () -> view.set(switch (order.getPaymentMethod()) {
                case STRIPE -> stripe.inspect(order.getId());
                case WEBPAY -> webpay.inspect(order.getId());
                case MERCADOPAGO -> mercadoPago.inspect(order.getId());
                case MANUAL -> Optional.empty();
            }));
            return new Lookup(view.get(), null);
        } catch (RuntimeException e) {
            log.warn("Informe de recuperación: no se pudo consultar la pasarela de order_id={} error={}", order.getId(),
                    e.getMessage());
            return new Lookup(Optional.empty(), shorten(e.getMessage()));
        }
    }

    private PaymentFinding paymentFinding(Order order, Lookup lookup) {
        BigDecimal localRefunded = completedRefunds(order.getId());
        ProviderPaymentView view = lookup.view().orElse(null);
        PaymentCheck check;
        String detail = null;
        boolean localCharged = order.getPaymentStatus() == PaymentStatus.PAID
                || order.getPaymentStatus() == PaymentStatus.REFUNDED;

        if (lookup.error() != null) {
            check = PaymentCheck.NO_CONSULTABLE;
            detail = lookup.error();
        } else if (view == null || !view.charged()) {
            check = localCharged ? PaymentCheck.LOCAL_PAGADO_PROVEEDOR_NO : PaymentCheck.COINCIDE;
            if (view == null) {
                detail = "La pasarela no tiene operación para esta orden";
            }
        } else if (!localCharged) {
            check = PaymentCheck.PROVEEDOR_PAGADO_LOCAL_NO;
        } else if (view.amount() == null || view.amount().compareTo(order.getTotal()) != 0) {
            check = PaymentCheck.MONTO_DISTINTO;
        } else if (view.refunded() != null && view.refunded().compareTo(localRefunded) != 0) {
            check = PaymentCheck.REEMBOLSO_DISTINTO;
        } else {
            check = PaymentCheck.COINCIDE;
        }

        return new PaymentFinding(order.getId(), order.getOrderNumber(), order.getPaymentMethod().name(),
                order.getPaymentStatus().name(), order.getStatus().name(), order.getTotal(), localRefunded,
                view == null ? null : view.providerStatus(), view == null ? null : view.amount(),
                view == null ? null : view.refunded(), view == null ? null : view.reference(), check, detail);
    }

    private BigDecimal completedRefunds(Long orderId) {
        return refundRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .filter(refund -> refund.getStatus() == RefundStatus.COMPLETED)
                .map(OrderRefund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---------------------------------------------------------------- reembolsos

    private RefundFinding refundFinding(OrderRefund refund, Lookup lookup) {
        Order order = refund.getOrder();
        String method = order.getPaymentMethod() == null ? null : order.getPaymentMethod().name();
        RefundCheck check;
        String detail = null;
        if (refund.getStatus() != RefundStatus.PENDING) {
            check = RefundCheck.REGISTRADO;
        } else if (lookup == null || lookup.error() != null || lookup.view().isEmpty()
                || lookup.view().get().refunded() == null) {
            check = RefundCheck.NO_CONSULTABLE;
            detail = lookup == null ? null : lookup.error();
        } else {
            BigDecimal completed = completedRefunds(order.getId());
            BigDecimal providerRefunded = lookup.view().get().refunded();
            if (providerRefunded.compareTo(completed.add(refund.getAmount())) >= 0) {
                check = RefundCheck.PROVEEDOR_DEVOLVIO;
            } else if (providerRefunded.compareTo(completed) <= 0) {
                check = RefundCheck.PROVEEDOR_NO_DEVOLVIO;
            } else {
                check = RefundCheck.NO_CONCLUYENTE;
            }
            detail = "Devuelto según la pasarela: " + providerRefunded.toPlainString() + "; completado en la base: "
                    + completed.toPlainString();
        }
        return new RefundFinding(refund.getId(), order.getId(), order.getOrderNumber(), method, refund.getAmount(),
                refund.getStatus().name(), refund.getCreatedAt(), check, detail);
    }

    // ---------------------------------------------------------------- outbox

    private static OutboxFinding outboxFinding(OutboxMessage message) {
        OutboxCheck check = message.getType().channel() == OutboxChannel.POS
                ? OutboxCheck.INCIERTO_POS
                : OutboxCheck.INCIERTO_CORREO;
        return new OutboxFinding(message.getId(), message.getType().name(), message.getStatus().name(),
                message.getOrder() == null ? null : message.getOrder().getOrderNumber(), message.getCompanyId(),
                message.getAttempts(), message.getCreatedAt(), check);
    }

    // ---------------------------------------------------------------- comunes

    private static final class Section {
        private final Store store;
        private final List<PaymentFinding> payments = new ArrayList<>();
        private final List<RefundFinding> refunds = new ArrayList<>();
        private final List<OutboxFinding> outbox = new ArrayList<>();

        private Section(Store store) {
            this.store = store;
        }
    }

    private static Section section(Map<Long, Section> sections, Store store) {
        return sections.computeIfAbsent(store.getId(), id -> new Section(store));
    }

    private static String shorten(String message) {
        if (message == null) {
            return "Error sin mensaje";
        }
        return message.length() <= MAX_DETAIL ? message : message.substring(0, MAX_DETAIL) + "…";
    }
}
