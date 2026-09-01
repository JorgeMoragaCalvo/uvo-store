package org.uvo.uvostore.service.order;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.OrderStatusHistory;
import org.uvo.uvostore.repository.CouponUsageRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;

import java.util.ArrayList;
import java.util.List;

// C5: everything that moves stock or a coupon use because of an order's lifecycle lives here.
//
// It exists because four separate paths can cancel an order — OrderStatusService.markCancelled and
// .markPaymentFailed, AdminOrderService.cancelOrder and .updateStatus — and each of them would
// otherwise need its own copy of "was this order's stock ever applied? has it already been
// returned?". Getting that wrong doesn't lose data, it invents it: a second cancellation would add
// inventory that never existed.
//
// The stockApplied/stockRestored flags on the order (V13) are the source of truth for that, not the
// order's status, because status can be set to CANCELLED from several places and back again.
@Service
public class OrderInventoryService {

    private static final Logger log = LoggerFactory.getLogger(OrderInventoryService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final CouponService couponService;

    public OrderInventoryService(OrderRepository orderRepository, ProductRepository productRepository,
                                 ProductVariationRepository variationRepository,
                                 CouponUsageRepository couponUsageRepository, CouponService couponService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.couponService = couponService;
    }

    /**
     * Takes the order's items out of stock. Called once, when payment is confirmed.
     *
     * <p>A line that can't be decremented never aborts the rest of the order (the behaviour ported
     * from Laravel's queued listener), but it is no longer swallowed: every failure ends up in the
     * order's own status history and in Sentry, because by this point the customer has already paid
     * and somebody has to fix it by hand.
     */
    @Transactional
    public void applyOrderStock(Order order) {
        if (order.isStockApplied()) {
            log.info("Stock ya aplicado, se omite order_id={}", order.getId());
            return;
        }

        List<String> failures = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            if (item.getVariation() != null) {
                decrementVariation(item, failures);
            } else {
                decrementSimpleProduct(item, failures);
            }
        }

        order.setStockApplied(true);
        if (!failures.isEmpty()) {
            reportStockFailure(order, failures);
        }
        orderRepository.save(order);
    }

    /**
     * Puts the order's items back into stock. Only does anything if the stock was actually taken
     * (i.e. the order reached PAID) and hasn't been returned yet — cancelling an unpaid order is a
     * no-op, because nothing was ever decremented.
     */
    @Transactional
    public void restoreOrderStock(Order order) {
        if (!order.isStockApplied() || order.isStockRestored()) {
            return;
        }

        for (OrderItem item : order.getItems()) {
            if (item.getVariation() != null) {
                variationRepository.restoreStock(item.getVariation().getId(), item.getQuantity());
                productRepository.recalculateStockFromVariations(item.getProduct().getId());
            } else if (item.getProduct().isManageStock()) {
                productRepository.restoreStock(item.getProduct().getId(), item.getQuantity());
            }
        }

        order.setStockRestored(true);
        orderRepository.save(order);
        log.info("Stock restaurado por cancelación order_id={} order_number={}", order.getId(), order.getOrderNumber());
    }

    /**
     * Gives the coupon use back when an order is cancelled or its payment fails. The use is claimed
     * at checkout (it acts as a reservation, which is what actually enforces the limit), so without
     * this an abandoned cart would burn it forever.
     *
     * <p>Deleting the usage row is the idempotency guard: UNIQUE(coupon_id, order_id) guarantees at
     * most one, so a delete that affects no rows means the use was already returned.
     */
    @Transactional
    public void releaseCouponUsage(Order order) {
        if (order.getCoupon() == null) {
            return;
        }
        int deleted = couponUsageRepository.deleteByOrderId(order.getId());
        if (deleted == 0) {
            return;
        }
        couponService.releaseUsage(order.getCoupon());
        log.info("Uso de cupón devuelto order_id={} coupon_id={}", order.getId(), order.getCoupon().getId());
    }

    private void decrementVariation(OrderItem item, List<String> failures) {
        Long variationId = item.getVariation().getId();
        int updated = variationRepository.decrementStock(variationId, item.getQuantity());
        if (updated == 0) {
            failures.add("variación " + item.getProductSku() + " (id " + variationId + ", cantidad " + item.getQuantity() + ")");
            return;
        }
        // Parent Product.stock is the sum of its variations, so it has to follow. Recomputed in one
        // statement rather than through recalculateParentAggregate, which also touches minPrice and
        // needs TenantContext — neither belongs in an AFTER_COMMIT listener.
        productRepository.recalculateStockFromVariations(item.getProduct().getId());
    }

    private void decrementSimpleProduct(OrderItem item, List<String> failures) {
        if (!item.getProduct().isManageStock()) {
            return;
        }
        Long productId = item.getProduct().getId();
        int updated = productRepository.decrementStock(productId, item.getQuantity());
        if (updated == 0) {
            failures.add("producto " + item.getProductSku() + " (id " + productId + ", cantidad " + item.getQuantity() + ")");
        }
    }

    private void reportStockFailure(Order order, List<String> failures) {
        String detail = "Stock insuficiente al confirmar el pago: " + String.join("; ", failures);

        // The order's own history, so whoever opens it in the admin panel sees the problem without
        // going anywhere else. The status is left untouched: the order IS paid, it just can't be
        // fulfilled as it stands.
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setStatus(order.getStatus().name());
        history.setNotes(detail);
        order.getStatusHistory().add(history);

        log.error("{} order_id={} order_number={}", detail, order.getId(), order.getOrderNumber());
        // Explicit: this runs in a listener that deliberately doesn't throw, so it would never reach
        // GlobalExceptionHandler's Sentry reporting on its own.
        Sentry.captureMessage(detail + " [order_number=" + order.getOrderNumber() + "]");
    }
}
