package org.uvo.uvostore.service.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderStatusHistory;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;

import java.time.Instant;
import java.util.NoSuchElementException;

@Service
public class AdminOrderServiceImpl implements AdminOrderService {

    private final OrderRepository orderRepository;
    private final AdminOrderQueryService adminOrderQueryService;
    private final OrderInventoryService orderInventoryService;
    private final OrderStatusService orderStatusService;

    public AdminOrderServiceImpl(OrderRepository orderRepository, AdminOrderQueryService adminOrderQueryService,
                                 OrderInventoryService orderInventoryService, OrderStatusService orderStatusService) {
        this.orderRepository = orderRepository;
        this.adminOrderQueryService = adminOrderQueryService;
        this.orderInventoryService = orderInventoryService;
        this.orderStatusService = orderStatusService;
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
        order.setStatus(OrderStatus.CANCELLED);
        appendHistory(order, "Orden cancelada");
        releaseInventory(order);
        orderRepository.save(order);
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
        }

        order.setStatus(newStatus);
        appendHistory(order, "Estado actualizado manualmente");
        if (newStatus == OrderStatus.CANCELLED) {
            releaseInventory(order);
        }
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    @Override
    @Transactional
    public AdminOrderDetailDto updatePaymentStatus(Long orderId, String paymentStatus) {
        Order order = findOrThrow(orderId);
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
        return adminOrderQueryService.getById(orderId);
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
