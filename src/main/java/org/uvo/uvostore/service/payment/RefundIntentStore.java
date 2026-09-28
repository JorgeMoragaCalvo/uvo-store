package org.uvo.uvostore.service.payment;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderRefund;
import org.uvo.uvostore.entity.order.enums.RefundStatus;
import org.uvo.uvostore.entity.order.enums.RefundType;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.UserRepository;

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
 */
@Component
public class RefundIntentStore {

    private final OrderRefundRepository refundRepository;
    private final UserRepository userRepository;

    public RefundIntentStore(OrderRefundRepository refundRepository, UserRepository userRepository) {
        this.refundRepository = refundRepository;
        this.userRepository = userRepository;
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
        return refundRepository.save(OrderRefund.builder()
                .order(order)
                .amount(amount)
                .type(type)
                .reason(command.reason())
                .user(user)
                .status(RefundStatus.PENDING)
                .idempotencyKey(key)
                .build());
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
        });
    }
}
