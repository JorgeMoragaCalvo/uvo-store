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
        if (newStatus == PaymentStatus.PAID) {
            orderStatusService.markPaid(orderId, "manual:" + order.getOrderNumber(), order.getTotal());
            return adminOrderQueryService.getById(orderId);
        }

        order.setPaymentStatus(newStatus);
        orderRepository.save(order);
        return adminOrderQueryService.getById(orderId);
    }

    // G4: marcar una orden como devuelta dejó de ser un cambio de estado. Antes, estos dos endpoints
    // la ponían en REFUNDED sin llamar a ninguna pasarela — la base decía "devuelto" y el dinero
    // seguía cobrado. Ahora el único camino es RefundService, que primero mueve el dinero; y para lo
    // que ya se devolvió por fuera está el registro de reembolso externo, que exige motivo.
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
