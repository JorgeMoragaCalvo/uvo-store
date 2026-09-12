package org.uvo.uvostore.service.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderRefund;
import org.uvo.uvostore.entity.order.OrderStatusHistory;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.order.enums.RefundType;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OrderStatusHistoryRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.order.OrderStatusService;
import org.uvo.uvostore.service.order.PaymentService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NoSuchElementException;

/**
 * G4. Devolver dinero. Hasta aquí {@code PaymentStatus.REFUNDED} existía en el enum y no había nada
 * detrás: el panel marcaba una orden como devuelta, restauraba el inventario y no llamaba a ninguna
 * pasarela. Se podía vender y no se podía devolver, y dos clics dejaban la base diciendo "devuelto"
 * mientras la pasarela seguía diciendo "cobrado".
 *
 * <p>Este servicio es el despachador —el equivalente de {@code PaymentReconciliationService} para el
 * camino contrario—: valida cuánto se puede devolver, le pide a la pasarela de la orden que lo haga,
 * y solo entonces escribe. <b>El orden importa</b>: si la pasarela falla, lanza, la transacción se
 * deshace y no queda registro de un reembolso que no ocurrió.
 *
 * <p>No hace falta {@code TenantContext.runWithin} como en la conciliación: esto siempre sale de una
 * petición del panel, así que {@code TenantResolutionFilter} ya puso el tenant.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final OrderRepository orderRepository;
    private final OrderRefundRepository refundRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final OrderStatusService orderStatusService;
    private final PaymentService paymentService;
    private final WebpayService webpayService;
    private final MercadoPagoService mercadoPagoService;

    public RefundService(OrderRepository orderRepository, OrderRefundRepository refundRepository,
                         OrderStatusHistoryRepository historyRepository, UserRepository userRepository,
                         OrderStatusService orderStatusService, PaymentService paymentService,
                         WebpayService webpayService, MercadoPagoService mercadoPagoService) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.orderStatusService = orderStatusService;
        this.paymentService = paymentService;
        this.webpayService = webpayService;
        this.mercadoPagoService = mercadoPagoService;
    }

    /**
     * Devuelve dinero a través de la pasarela con que se cobró la orden.
     */
    @Transactional
    public OrderRefund refund(RefundCommand command) {
        Order order = lockedOrder(command.orderId());
        BigDecimal amount = resolveAmount(order, command.amount());
        // Se calcula ANTES de escribir nada: en cuanto se guarde la fila, el saldo cambia.
        boolean closesOrder = closesOrder(order, amount);

        String reference = switch (order.getPaymentMethod()) {
            case STRIPE -> paymentService.refund(order.getId(), amount);
            case WEBPAY -> webpayService.refund(order.getId(), amount);
            case MERCADOPAGO -> mercadoPagoService.refund(order.getId(), amount);
            // No hay pasarela contra la que pedir nada: una transferencia se devuelve por el banco,
            // y luego se registra aquí con recordExternal.
            case MANUAL -> throw new BusinessException(
                    "Esta orden se pagó fuera de una pasarela: registra el reembolso con el endpoint de reembolso externo");
        };

        return register(order, amount, reference, closesOrder ? RefundType.FULL : RefundType.PARTIAL,
                closesOrder, command);
    }

    /**
     * Registra un reembolso que ya se hizo fuera de este sistema —en el panel de Transbank, Stripe o
     * MercadoPago—, sin llamar a nadie. Existe porque la alternativa es peor: si no hay forma de
     * anotarlo, la base se queda diciendo que la orden sigue cobrada y nadie lo corrige nunca.
     */
    @Transactional
    public OrderRefund recordExternal(RefundCommand command) {
        if (command.reason() == null || command.reason().isBlank()) {
            // Es el único dato que explica un movimiento que este sistema no puede verificar.
            throw new BusinessException("Un reembolso externo necesita un motivo");
        }
        Order order = lockedOrder(command.orderId());
        BigDecimal amount = resolveAmount(order, command.amount());
        // El tipo es EXTERNAL lo cubra todo o no —lo que distingue a estos es que el dinero no se
        // movió desde aquí—, así que si cierra la orden se decide aparte, por el saldo.
        return register(order, amount, null, RefundType.EXTERNAL, closesOrder(order, amount), command);
    }

    private OrderRefund register(Order order, BigDecimal amount, String reference, RefundType type,
                                 boolean closesOrder, RefundCommand command) {
        User user = command.userId() == null ? null : userRepository.findById(command.userId()).orElse(null);
        OrderRefund refund = OrderRefund.builder()
                .order(order)
                .amount(amount)
                .gatewayReference(reference)
                .type(type)
                .reason(command.reason())
                .user(user)
                .build();
        refund = refundRepository.save(refund);

        String detail = describe(type, amount, reference, command.reason());
        log.info("Reembolso registrado order_id={} order_number={} monto={} tipo={} referencia={}",
                order.getId(), order.getOrderNumber(), amount, type, reference);

        if (closesOrder) {
            // Se devolvió todo el saldo: la compra queda deshecha. markRefunded es idempotente y
            // devuelve stock y cupón, igual que una cancelación.
            orderStatusService.markRefunded(order.getId(), detail);
        } else {
            // Una devolución parcial no deshace la compra: la orden sigue PAID, y el stock y el cupón
            // se quedan como están. Solo queda anotada.
            appendHistory(order, detail);
        }
        return refund;
    }

    /**
     * La orden bloqueada hasta el fin de la transacción, y con el saldo listo para validarse. El
     * cerrojo es lo que impide que dos reembolsos simultáneos lean el mismo "ya devuelto" y entre los
     * dos autoricen más dinero del que se cobró.
     */
    private Order lockedOrder(Long orderId) {
        Long storeId = TenantContext.requireStoreId();
        Order order = orderRepository.findByIdForUpdate(orderId)
                .filter(o -> o.getStore().getId().equals(storeId))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));

        // Una orden ya devuelta del todo queda REFUNDED, así que este guard también cierra el paso a
        // reembolsar dos veces lo mismo. Una con reembolsos parciales sigue PAID y puede recibir más.
        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            throw new BusinessException("Solo se puede reembolsar una orden pagada (esta está "
                    + order.getPaymentStatus() + ")");
        }
        return order;
    }

    private BigDecimal resolveAmount(Order order, BigDecimal requested) {
        BigDecimal remaining = remaining(order);
        if (requested == null) {
            // Null es "todo lo que quede": el caso normal, y así el llamador no tiene que calcular el
            // saldo para un reembolso total.
            return remaining;
        }
        BigDecimal amount = requested.setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El monto a reembolsar debe ser mayor que cero");
        }
        if (amount.compareTo(remaining) > 0) {
            throw new BusinessException("No se puede reembolsar " + amount + ": de esta orden quedan "
                    + remaining + " por devolver");
        }
        return amount;
    }

    private BigDecimal remaining(Order order) {
        BigDecimal alreadyRefunded = refundRepository.totalRefunded(order.getId());
        return order.getTotal().subtract(alreadyRefunded).setScale(2, RoundingMode.HALF_UP);
    }

    // Cierra la orden si se lleva todo lo que quedaba. Lo decide el saldo y no el llamador, para que
    // no puedan discrepar — y se consulta antes de guardar el reembolso, que cambia ese saldo.
    private boolean closesOrder(Order order, BigDecimal amount) {
        return amount.compareTo(remaining(order)) == 0;
    }

    private String describe(RefundType type, BigDecimal amount, String reference, String reason) {
        String detail = switch (type) {
            case FULL -> "Reembolso total de " + amount;
            case PARTIAL -> "Reembolso parcial de " + amount;
            case EXTERNAL -> "Reembolso de " + amount + " hecho fuera del sistema y registrado a mano";
        };
        if (reference != null) {
            detail += " (referencia " + reference + ")";
        }
        if (reason != null && !reason.isBlank()) {
            detail += ". Motivo: " + reason;
        }
        return detail;
    }

    private void appendHistory(Order order, String notes) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setStatus(order.getStatus().name());
        history.setNotes(notes);
        historyRepository.save(history);
    }
}
