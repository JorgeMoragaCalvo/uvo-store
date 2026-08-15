package org.uvo.uvostore.repository;

import org.aspectj.weaver.ast.Or;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByOrderNumber(String orderNumber);
    Optional<Order> findByStripeCheckoutSessionId(String sessionId);
    Optional<Order> findByStripePaymentIntentId(String paymentIntentId);
    Optional<Order> findByPosOrderId(String posOrderId);

    List<Order> findByStatus(OrderStatus status); // Order::byStatus()
    List<Order> findByPaymentStatus(PaymentStatus paymentStatus); // Order::byPaymentStatus()
    List<Order> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<Order> findTop10ByOrderByCreatedAtDesc(); // Order::recent()
    List<Order> findByStatusAndCreatedAtAfter(OrderStatus status, Instant since);
    long countByPosSyncedFalse();
}
