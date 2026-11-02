package org.uvo.uvostore.service.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderStatusHistory;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;

import java.time.Instant;
import java.util.NoSuchElementException;

@Service
public class AdminOrderServiceImpl implements AdminOrderService {

    private final OrderRepository orderRepository;
    private final AdminOrderQueryService adminOrderQueryService;
    private final OrderInventoryService orderInventoryService;
    private final OrderStatusService orderStatusService;
    private final AuditTrail auditTrail;

    public AdminOrderServiceImpl(OrderRepository orderRepository, AdminOrderQueryService adminOrderQueryService,
                                 OrderInventoryService orderInventoryService, OrderStatusService orderStatusService,
                                 AuditTrail auditTrail) {
        this.orderRepository = orderRepository;
        this.adminOrderQueryService = adminOrderQueryService;
        this.orderInventoryService = orderInventoryService;
        this.orderStatusService = orderStatusService;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional
    public AdminOrderDetailDto markProcessing(Long orderId) {
        Order order = findOrThrow(orderId);
        order.setStatus(OrderStatus.PROCESSING);
        appendHistory(order, "Marcada como en proceso");
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    @Override
    @Transactional
    public AdminOrderDetailDto markShipped(Long orderId) {
        Order order = findOrThrow(orderId);
        requirePaid(order, "enviar");
        requireStock(order);
        order.setStatus(OrderStatus.SHIPPED);
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        order.setShippedAt(Instant.now());
        appendHistory(order, "Marcada como enviada");
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    @Override
    @Transactional
    public AdminOrderDetailDto markDelivered(Long orderId) {
        Order order = findOrThrow(orderId);
        requirePaid(order, "marcar como entregada");
        requireStock(order);
        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(Instant.now());
        appendHistory(order, "Marcada como entregada");
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    @Override
    @Transactional
    public AdminOrderDetailDto cancelOrder(Long orderId) {
        Order order = findOrThrow(orderId);
        OrderStatus previous = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        appendHistory(order, "Orden cancelada");
        releaseInventory(order);
        orderRepository.save(order);
        auditCancel(order, previous, "cancel");
        return adminOrderQueryService.getById(orderId);
    }

    @Override
    @Transactional
    public AdminOrderDetailDto updateStatus(Long orderId, String status) {
        Order order = findOrThrow(orderId);
        OrderStatus newStatus = OrderStatus.valueOf(status.toUpperCase());
        guardAgainstManualRefund(newStatus == OrderStatus.REFUNDED);

        // F14. OrderStatus.PAID existe en el enum y no se usa en ninguna parte del código —markPaid deja
        // la orden en PROCESSING—, así que la única forma de llegar a él era este setter. Tener dos
        // nociones de "pagado" sin nada que las sincronice no ayuda a nadie: el estado de pago se cambia
        // por su propio endpoint. No se toca el enum (eso es una migración), solo se cierra la puerta.
        if (newStatus == OrderStatus.PAID) {
            throw new BusinessException("El estado de pago se cambia con PUT /payment-status, no aquí");
        }

        // F14. Una orden cancelada cuyo stock ya se devolvió no revive. OrderInventoryService bloquea
        // reaplicar un stock ya restaurado (por stockRestored), así que reactivarla dejaría el inventario
        // sobrecontado para siempre y sin vuelta atrás. Es el único de estos huecos que hace daño
        // permanente.
        boolean revives = newStatus == OrderStatus.PROCESSING || newStatus == OrderStatus.SHIPPED
                || newStatus == OrderStatus.DELIVERED;
        if (revives && order.isStockRestored()) {
            throw new BusinessException("Esta orden ya devolvió su stock al cancelarse y no se puede "
                    + "reactivar: el inventario quedaría descuadrado. Crea una orden nueva.");
        }
        if (newStatus == OrderStatus.SHIPPED || newStatus == OrderStatus.DELIVERED) {
            requirePaid(order, "pasar a " + newStatus);
            requireStock(order);
        }

        OrderStatus previous = order.getStatus();
        order.setStatus(newStatus);
        appendHistory(order, "Estado actualizado manualmente");
        if (newStatus == OrderStatus.CANCELLED) {
            releaseInventory(order);
        }
        orderRepository.save(order);
        if (newStatus == OrderStatus.CANCELLED) {
            auditCancel(order, previous, "status");
        }
        return adminOrderQueryService.getById(orderId);
    }

    @Override
    @Transactional
    public AdminOrderDetailDto updatePaymentStatus(Long orderId, String paymentStatus) {
        Order order = findOrThrow(orderId);
        PaymentStatus previous = order.getPaymentStatus();
        PaymentStatus newStatus = PaymentStatus.valueOf(paymentStatus.toUpperCase());
        guardAgainstManualRefund(newStatus == PaymentStatus.REFUNDED);

        // F07. Confirmar un pago deja de ser un setter y pasa por markPaid, que es donde vive lo que
        // significa cobrar: publica PaymentConfirmedEvent y con él se descuenta el stock, se emite el
        // documento al POS y sale el correo de compra confirmada.
        //
        // Antes esto solo escribía la columna, así que una orden pagada por transferencia —el método
        // principal de muchas tiendas— se quedaba PAID pero **sin descontar stock nunca**, sin boleta
        // y sin correo. No estaba en la auditoría; salió al mover los efectos al pago confirmado.
        //
        // El importe que se pasa es el total de la orden: el operador está afirmando que recibió el
        // ingreso íntegro, que es justamente lo que confirma a mano. El resto de estados sigue siendo
        // un cambio de columna — la máquina de transiciones completa es F14.
        // F14. Y ningún estado de pago se escribe ya como columna: todos pasan por OrderStatusService,
        // que es donde viven las precondiciones y los efectos. Este setter era la puerta por la que el
        // panel podía degradar una orden pagada a FAILED — el mismo daño que F03 cerró para los webhooks
        // tardíos, porque esa guarda (canFail) vive en el servicio y esta ruta lo esquivaba. Y era menos
        // coherente que el webhook: markPaymentFailed al menos devuelve el uso del cupón, y escribir la
        // columna a pelo dejaba FAILED con el cupón consumido y el stock descontado.
        switch (newStatus) {
            case PAID -> orderStatusService.markPaid(orderId, "manual:" + order.getOrderNumber(), order.getTotal());
            case FAILED -> orderStatusService.markPaymentFailed(orderId);
            // Deshacer un pago no es un cambio de columna. Si el dinero se devolvió, eso es un reembolso
            // (que tiene su propio endpoint y mueve dinero de verdad); si nunca entró, la orden ya está
            // en PENDING y no hay nada que hacer.
            case PENDING -> throw new BusinessException("Una orden no vuelve a 'pendiente de pago' a mano: "
                    + "si devolviste el dinero usa el reembolso, y si el pago nunca entró la orden ya está pendiente");
            // Lo captura guardAgainstManualRefund más arriba; está aquí para que el switch sea exhaustivo
            // y un valor nuevo del enum no se cuele por un default silencioso.
            case REFUNDED -> throw new IllegalStateException("inalcanzable: lo rechaza guardAgainstManualRefund");
        }
        // PROD-18. Marcar pagada a mano entrega mercadería sin que haya pasado un cobro por aquí: es lo que más
        // conviene poder atribuir. markPaid y markPaymentFailed cambian esta misma entidad (misma transacción),
        // y son idempotentes: si no cambió nada, no hay fila.
        AuditDiff diff = AuditDiff.create().value("paymentStatus", previous, order.getPaymentStatus());
        if (diff.hasChanges()) {
            auditTrail.record(AuditEntry.of(AuditAction.ORDER_PAYMENT_STATUS_UPDATE, AuditTargetType.ORDER, order.getId())
                    .details(diff.with("orderNumber", order.getOrderNumber())));
        }
        return adminOrderQueryService.getById(orderId);
    }

    // PROD-18. Las dos vías del panel que cancelan (el botón y el cambio de estado). Cancelar devuelve stock y
    // cupón; cancelar una ya cancelada no hace nada y no deja fila.
    private void auditCancel(Order order, OrderStatus previous, String via) {
        if (previous == OrderStatus.CANCELLED) {
            return;
        }
        auditTrail.record(AuditEntry.of(AuditAction.ORDER_CANCEL, AuditTargetType.ORDER, order.getId())
                .details(AuditDiff.create()
                        .with("orderNumber", order.getOrderNumber())
                        .with("fromStatus", previous)
                        .with("paymentStatus", order.getPaymentStatus())
                        .with("via", via)));
    }

    // G4: marcar una orden como devuelta dejó de ser un cambio de estado. Antes, estos dos endpoints
    // la ponían en REFUNDED sin llamar a ninguna pasarela — la base decía "devuelto" y el dinero
    // seguía cobrado. Ahora el único camino es RefundService, que primero mueve el dinero; y para lo
    // que ya se devolvió por fuera está el registro de reembolso externo, que exige motivo.
    /**
     * F14. No se despacha lo que no está pagado.
     *
     * <p>{@code markProcessing} se queda libre a propósito: es la bandeja de trabajo del comerciante
     * —preparar el pedido mientras espera la transferencia es legítimo— y no mueve dinero ni stock. Enviar
     * y entregar sí son irreversibles de hecho, y ahí sí hace falta que el dinero esté.
     *
     * <p>{@code REFUNDED} pasa: hay que poder cerrar el rastro de un envío al que después se le devolvió
     * el dinero.
     */
    private void requirePaid(Order order, String action) {
        PaymentStatus payment = order.getPaymentStatus();
        if (payment != PaymentStatus.PAID && payment != PaymentStatus.REFUNDED) {
            throw new BusinessException("No se puede " + action + " una orden cuyo pago está en "
                    + payment + ": confirma el pago primero");
        }
    }

    private void guardAgainstManualRefund(boolean isRefund) {
        if (isRefund) {
            throw new BusinessException("Una orden no se marca como reembolsada a mano: usa el reembolso "
                    + "(POST /api/admin/orders/{id}/refund) o, si ya devolviste el dinero desde la pasarela, "
                    + "regístralo con POST /api/admin/orders/{id}/refund/external");
        }
    }

    @Override
    @Transactional
    public AdminOrderDetailDto saveTracking(Long orderId, String trackingNumber) {
        Order order = findOrThrow(orderId);
        // F14: este endpoint también pone SHIPPED, así que sin esta comprobación la guarda de markShipped
        // se saltaba simplemente guardando un número de seguimiento. Lo descubrió un test que ya existía
        // y que despachaba una orden impaga sin que nadie lo hubiera notado.
        requirePaid(order, "guardar el seguimiento y enviar");
        requireStock(order);
        order.setTrackingNumber(trackingNumber);
        order.setStatus(OrderStatus.SHIPPED);
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        if (order.getShippedAt() == null) {
            order.setShippedAt(Instant.now());
        }
        appendHistory(order, "Número de seguimiento guardado");
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    /**
     * GAP-03. «Reintentar descuento»: la orden se cobró sin stock para alguna línea y ya llegó reposición. Si ahora
     * se descuentan todas, la orden queda desbloqueada; si no, sigue marcada y la nota del historial dice qué falta.
     */
    @Override
    @Transactional
    public AdminOrderDetailDto retryStock(Long orderId) {
        Order order = lockedShortageOrder(orderId);
        if (order.getPaymentStatus() != PaymentStatus.PAID || order.isStockRestored()) {
            throw new BusinessException("Solo se reintenta el descuento de una orden pagada que no se haya cancelado");
        }
        orderInventoryService.retryMissingStock(order);
        return adminOrderQueryService.getById(orderId);
    }

    /**
     * GAP-03. «Liberar bloqueo»: el comerciante resolvió por fuera lo que faltaba (lo consiguió de otra parte, o
     * reembolsó esa parte) y quiere despachar. No mueve stock: las líneas que no se descontaron siguen sin marca, así
     * que una cancelación posterior no devuelve unidades que nunca salieron (F04).
     *
     * <p>La nota es obligatoria porque el sistema no puede comprobar lo que afirma, y va al historial de la orden, no
     * a la auditoría: es texto libre, la misma regla que el motivo de un reembolso.
     */
    @Override
    @Transactional
    public AdminOrderDetailDto releaseStockShortage(Long orderId, String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessException("Liberar el bloqueo necesita una nota que explique cómo se resolvió");
        }
        Order order = lockedShortageOrder(orderId);
        long missingLines = order.getItems().stream()
                .filter(item -> !item.isStockApplied()
                        && (item.getVariation() != null || item.getProduct().isManageStock()))
                .count();

        order.setStockShortage(false);
        appendHistory(order, "Bloqueo por falta de stock liberado. Nota: " + note.trim());
        orderRepository.save(order);
        auditTrail.record(AuditEntry.of(AuditAction.ORDER_STOCK_SHORTAGE_RELEASE, AuditTargetType.ORDER, order.getId())
                .details(AuditDiff.create()
                        .with("orderNumber", order.getOrderNumber())
                        .with("missingLines", missingLines)));
        return adminOrderQueryService.getById(orderId);
    }

    /**
     * GAP-03. Con cerrojo: dos reintentos a la vez descontarían dos veces la misma línea, y un reintento a la vez que
     * una liberación podría dejar la marca puesta después de liberarla.
     */
    private Order lockedShortageOrder(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .filter(o -> o.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
        if (!order.isStockShortage()) {
            throw new BusinessException("Esta orden no tiene productos sin stock pendientes");
        }
        return order;
    }

    /**
     * GAP-03. Una orden cobrada a la que le faltó stock al confirmar el pago no se despacha. Preparar sí
     * ({@code markProcessing}), igual que con una orden impaga.
     */
    private void requireStock(Order order) {
        if (order.isStockShortage()) {
            throw new BusinessException("Esta orden tiene productos sin stock: reintenta el descuento cuando llegue la "
                    + "reposición, reembolsa o libera el bloqueo con una nota");
        }
    }

    // C5: an admin cancelling or refunding an order has to give the inventory back, the same way
    // the automatic cancellation path does. Both calls are guarded internally, so they do nothing
    // for an order that was never paid, and nothing again on a second cancellation.
    private void releaseInventory(Order order) {
        orderInventoryService.restoreOrderStock(order);
        orderInventoryService.releaseCouponUsage(order);
    }

    private void appendHistory(Order order, String notes) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setStatus(order.getStatus().name());
        history.setNotes(notes);
        order.getStatusHistory().add(history);
    }

    private Order findOrThrow(Long orderId) {
        return orderRepository.findById(orderId)
                .filter(o -> o.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
    }
}
