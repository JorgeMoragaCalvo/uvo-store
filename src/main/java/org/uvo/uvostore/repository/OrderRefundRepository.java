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
     *
     * <p>F12: cuenta también las intenciones {@code PENDING}, y deja fuera los {@code FAILED}. Es lo que
     * impide el doble reembolso: si la pasarela devolvió el dinero y el registro posterior se cayó, queda
     * una fila PENDING y ese importe deja de estar disponible, así que el reintento se rechaza en vez de
     * devolverlo otra vez. Un rechazo de la pasarela sí libera el saldo, porque ahí no se movió nada.
     */
    @Query("""
            select coalesce(sum(r.amount), 0) from OrderRefund r
            where r.order.id = :orderId
              and r.status <> org.uvo.uvostore.entity.order.enums.RefundStatus.FAILED
            """)
    BigDecimal totalRefunded(@Param("orderId") Long orderId);
}
