package org.uvo.uvostore.service.payment;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderRefund;
import org.uvo.uvostore.entity.order.enums.RefundStatus;
import org.uvo.uvostore.entity.order.enums.RefundType;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;

import java.math.BigDecimal;

/**
 * F12. La intención de devolver dinero, escrita y <b>confirmada</b> antes de llamar a la pasarela.
 *
 * <p>El fallo que esto cubre no es que la pasarela falle —eso lanza, se deshace todo y no queda registro
 * de un reembolso que no ocurrió, que es lo correcto— sino el contrario: la pasarela <b>devuelve el
 * dinero</b> y después se cae lo local. Antes eso dejaba el dinero fuera y la base diciendo que no se
 * había devuelto nada, así que {@code remaining()} ofrecía el saldo íntegro y el reintento del operador
 * lo devolvía por segunda vez.
 *
 * <p><b>Es un bean aparte a propósito, y no un método de {@code RefundService}.</b> Con
 * {@code REQUIRES_NEW} en un método de la misma clase la llamada no pasaría por el proxy de Spring, así
 * que la transacción nueva no se abriría nunca: la fila se escribiría en la transacción del reembolso y
 * se desharía con ella. El arreglo quedaría inerte y nada lo delataría. Si alguien vuelve a "simplificar"
 * esto metiéndolo en el servicio, deja de funcionar sin dar un solo error.
 *
 * <p>PROD-18. Las dos filas de auditoría del reembolso que tienen que sobrevivir a su rollback se escriben aquí,
 * dentro de estas mismas transacciones: la intención ({@code REFUND_REQUEST}), que queda aunque después la
 * transacción local se pierda, y el rechazo de la pasarela ({@code REFUND_ISSUE} con FAILURE).
 */
@Component
public class RefundIntentStore {

    private final OrderRefundRepository refundRepository;
    private final UserRepository userRepository;
    private final AuditTrail auditTrail;

    public RefundIntentStore(OrderRefundRepository refundRepository, UserRepository userRepository,
                             AuditTrail auditTrail) {
        this.refundRepository = refundRepository;
        this.userRepository = userRepository;
        this.auditTrail = auditTrail;
    }

    /**
     * Abre la intención y la deja commiteada. La clave se deriva de (orden, ya devuelto, importe), así
     * que el reintento de la <b>misma</b> devolución la repite —y choca con la restricción única de la
     * tabla, o la pasarela la deduplica— mientras que un segundo parcial legítimo, que parte de otro "ya
     * devuelto", genera una distinta.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderRefund open(Order order, BigDecimal amount, RefundType type, RefundCommand command) {
        User user = command.userId() == null ? null : userRepository.findById(command.userId()).orElse(null);
        String key = "refund:" + order.getId()
                + ":" + refundRepository.totalRefunded(order.getId()).setScale(0)
                + ":" + amount.setScale(0);
        OrderRefund intent = refundRepository.save(OrderRefund.builder()
                .order(order)
                .amount(amount)
                .type(type)
                .reason(command.reason())
                .user(user)
                .status(RefundStatus.PENDING)
                .idempotencyKey(key)
                .build());
        auditTrail.record(AuditEntry.of(AuditAction.REFUND_REQUEST, AuditTargetType.ORDER, order.getId())
                .details(refundDetails(intent, order)));
        return intent;
    }

    /**
     * La pasarela dijo no: el saldo vuelve a quedar disponible porque no se movió un peso. También en su
     * propia transacción, para que sobreviva al rollback del reembolso — sin esto, un rechazo dejaría
     * mordido para siempre un dinero que nunca salió.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long refundId) {
        refundRepository.findById(refundId).ifPresent(refund -> {
            refund.setStatus(RefundStatus.FAILED);
            refundRepository.save(refund);
            Order order = refund.getOrder();
            auditTrail.record(AuditEntry.of(AuditAction.REFUND_ISSUE, AuditTargetType.ORDER, order.getId())
                    .failed()
                    .details(refundDetails(refund, order)));
        });
    }

    // Ni el motivo (texto libre, ya está en order_refunds y en el historial) ni la clave de idempotencia.
    static AuditDiff refundDetails(OrderRefund refund, Order order) {
        return AuditDiff.create()
                .with("refundId", refund.getId())
                .with("orderNumber", order.getOrderNumber())
                .with("amount", refund.getAmount())
                .with("type", refund.getType())
                .with("paymentMethod", order.getPaymentMethod());
    }
}
