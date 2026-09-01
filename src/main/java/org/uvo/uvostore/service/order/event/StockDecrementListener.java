package org.uvo.uvostore.service.order.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.service.order.OrderInventoryService;

import java.util.NoSuchElementException;

// Ports ReduceStockOnPayment — runs AFTER_COMMIT on the transaction that confirmed payment
// (OrderStatusServiceImpl.markPaid), same trigger point as Laravel's queued listener on
// PaymentConfirmed.
//
// The decrement itself lives in OrderInventoryService, shared with the cancellation paths that
// restore stock. C5: it uses conditional UPDATEs instead of the read-check-write this class used
// to do, and a line that can't be decremented now leaves a note on the order and reaches Sentry
// instead of only a log line nobody reads.
@Component
public class StockDecrementListener {

    private static final Logger log = LoggerFactory.getLogger(StockDecrementListener.class);

    private final OrderRepository orderRepository;
    private final OrderInventoryService orderInventoryService;

    public StockDecrementListener(OrderRepository orderRepository, OrderInventoryService orderInventoryService) {
        this.orderRepository = orderRepository;
        this.orderInventoryService = orderInventoryService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new NoSuchElementException("Order " + event.orderId() + " not found"));

        log.info("Iniciando descuento de stock por pago confirmado order_id={} order_number={}", order.getId(), order.getOrderNumber());
        orderInventoryService.applyOrderStock(order);
        log.info("Descuento de stock completado order_id={}", order.getId());
    }
}
