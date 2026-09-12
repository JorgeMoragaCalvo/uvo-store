package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.order.OrderRefund;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface OrderRefundRepository extends JpaRepository<OrderRefund, Long> {

    List<OrderRefund> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    /**
     * G4. Lo ya devuelto de una orden, que es contra lo que se valida cada reembolso nuevo: la suma
     * de todos más el que se pide no puede pasar del total cobrado. {@code coalesce} porque una orden
     * sin reembolsos debe dar cero, no null.
     */
    @Query("select coalesce(sum(r.amount), 0) from OrderRefund r where r.order.id = :orderId")
    BigDecimal totalRefunded(@Param("orderId") Long orderId);
}
