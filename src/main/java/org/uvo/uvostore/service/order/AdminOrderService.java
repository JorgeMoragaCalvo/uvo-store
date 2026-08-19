package org.uvo.uvostore.service.order;

// Ports Admin\Orders\OrderIndex/OrderShow's write actions. Email notifications (OrderProcessing/
// OrderShipped/OrderDelivered mailables) are not sent — no JavaMailSender configured yet, same gap
// already flagged for checkout confirmation emails in Fase 4.
public interface AdminOrderService {
    AdminOrderDetailDto markProcessing(Long orderId);
    AdminOrderDetailDto markShipped(Long orderId);
    AdminOrderDetailDto markDelivered(Long orderId);
    AdminOrderDetailDto cancelOrder(Long orderId);
    AdminOrderDetailDto updateStatus(Long orderId, String status);
    AdminOrderDetailDto updatePaymentStatus(Long orderId, String paymentStatus);
    AdminOrderDetailDto saveTracking(Long orderId, String trackingNumber);
}
