package org.uvo.uvostore.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.order.Order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByOrderNumber(String orderNumber);
    Optional<Order> findByStripeCheckoutSessionId(String sessionId);
    Optional<Order> findByStripePaymentIntentId(String paymentIntentId);
    Optional<Order> findByPaymentId(String paymentId); // Webpay token / MercadoPago reference

    /**
     * G4. La orden bloqueada hasta el fin de la transacción.
     *
     * <p>Nació para el reembolso: lo devuelto se valida sumando {@code order_refunds} contra el total,
     * y dos reembolsos parciales simultáneos que leyeran la misma suma podrían autorizar entre los dos
     * más dinero del cobrado. Es el mismo razonamiento de C5 con el stock, resuelto aquí con un
     * cerrojo porque lo que hay que proteger es una suma sobre otra tabla, no un UPDATE atómico sobre
     * una columna.
     *
     * <p>F03: ahora también la usa {@code OrderStatusServiceImpl} en todo lo que cambia
     * {@code paymentStatus}, por el mismo motivo de fondo — la decisión se toma sobre un estado leído
     * antes de escribir, y sin cerrojo dos notificaciones simultáneas leen las dos el estado viejo.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    // PROD-17b. Se quitaron findByStatus, findByPaymentStatus, findTop10ByOrderByCreatedAtDesc,
    // findByStatusAndCreatedAtAfter, countByPosSyncedFalse y findByPosOrderId: ninguna filtraba por
    // tienda y ninguna tenía llamadores (eran los scopes de Laravel portados tal cual). Las dos primeras
    // listaban las órdenes de TODAS las tiendas. Las búsquedas por clave de pasarela de arriba siguen
    // siendo globales a propósito —el id lo emite la pasarela y es único—, pero quien las llama debe
    // comprobar la tienda de la orden: así lo hacen el seguimiento, MercadoPago y Webpay.
    List<Order> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    // PROD-13b: findPendingPosNotification (G2) se quitó con el reintento que la usaba. Solo veía órdenes con
    // syncAttempts >= 1, así que una venta cuyo proceso murió antes del primer intento no la veía nadie; ahora
    // cada venta al POS es una fila del outbox desde el commit del pago (OutboxMessageRepository).

    // G1: órdenes con el pago sin resolver que ya tienen identificador en la pasarela, es decir, las
    // que se pueden ir a preguntar. Se saltan a propósito las que tienen nota de descuadre de monto:
    // ésas no están esperando respuesta de la pasarela —ya llegó, y por otro importe—, están
    // esperando a una persona. Sin este filtro, cada corrida las volvería a mirar y a alertar para
    // siempre. Ver OrderStatusServiceImpl.AMOUNT_MISMATCH_PREFIX.
    @Query("""
            select o from Order o
            join fetch o.store
            where o.paymentStatus = org.uvo.uvostore.entity.order.enums.PaymentStatus.PENDING
              and o.createdAt < :notAfter
              and (o.paymentId is not null or o.stripeCheckoutSessionId is not null)
              and not exists (
                    select 1 from OrderStatusHistory h
                    where h.order = o and h.notes like :mismatchPrefix
              )
            order by o.createdAt asc
            """)
    List<Order> findPendingPaymentsToReconcile(@Param("notAfter") Instant notAfter,
                                               @Param("mismatchPrefix") String mismatchPrefix,
                                               Pageable pageable);

    /**
     * F15. Órdenes pendientes que nadie va a pagar, para soltar su reserva de cupón.
     *
     * <p><b>No</b> exige id de pasarela, y esa es la diferencia con
     * {@code findPendingPaymentsToReconcile}: las órdenes que interesan aquí son justamente las que se
     * quedaron sin él porque la llamada a la pasarela falló, así que la conciliación no las mira nunca —
     * ni las resuelve ni las alerta.
     *
     * <p>{@code join fetch o.store} por el mismo motivo que en la consulta del POS: lo consume un job, sin
     * sesión abierta, y lo primero que hace con cada orden es leer su tienda para fijar el tenant.
     */
    @Query("""
            select o from Order o
            join fetch o.store
            where o.status = org.uvo.uvostore.entity.order.enums.OrderStatus.PENDING
              and o.paymentStatus = org.uvo.uvostore.entity.order.enums.PaymentStatus.PENDING
              and o.createdAt < :notAfter
            order by o.createdAt asc
            """)
    List<Order> findAbandonedPending(@Param("notAfter") Instant notAfter, Pageable pageable);

    /**
     * PROD-13a. El reclamo del descuento de stock de una orden: la marca se pone en la misma sentencia
     * que la comprueba, así que de dos llamadas simultáneas (el listener del pago y el barrido) solo una
     * afecta la fila — la otra espera el cerrojo de fila, re-evalúa el WHERE y devuelve 0. Mismo
     * razonamiento que {@code ProductRepository.decrementStock} en C5.
     *
     * <p>Sin {@code clearAutomatically}: quien llama tiene la orden gestionada y todavía tiene que
     * recorrer sus líneas, que son perezosas.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Order o set o.stockApplied = true where o.id = :id and o.stockApplied = false")
    int claimStockApplication(@Param("id") Long id);

    /**
     * PROD-13a. Órdenes cobradas cuyo stock no llegó a descontarse: el proceso murió entre el commit de
     * {@code markPaid} y {@code StockDecrementListener}. El margen ({@code notAfter} sobre
     * {@code paidAt}) es para no competir con el listener de un pago recién confirmado — competir no
     * descontaría dos veces (lo impide el reclamo), pero alertaría de una anomalía que no existe.
     *
     * <p>{@code join fetch o.store} porque lo consume un job, sin sesión, que fija el tenant con ella.
     */
    @Query("""
            select o from Order o
            join fetch o.store
            where o.paymentStatus = org.uvo.uvostore.entity.order.enums.PaymentStatus.PAID
              and o.stockApplied = false
              and o.paidAt < :notAfter
            order by o.paidAt asc
            """)
    List<Order> findPaidWithoutStockApplied(@Param("notAfter") Instant notAfter, Pageable pageable);

    // Admin\Shipping\{Zones,Methods}\Index delete guards — refuse to delete a zone/method that
    // still has orders referencing it.
    long countByShippingZoneId(Long shippingZoneId);
    long countByShippingMethodRefId(Long shippingMethodId);

    // Admin\Reports\* — every report aggregates in-memory over the orders (+ items) in a date range.
    //
    // F20: tope EXCLUSIVO, no el `between` inclusivo de antes. El rango lo arma ReportDateRange a partir
    // de un día local, y su fin era `23:59:59`: con un between inclusivo, una orden creada a las
    // 23:59:59,4 —Postgres guarda microsegundos— se caía del informe de ese día y de cualquier otro.
    List<Order> findByStoreIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(Long storeId, Instant start, Instant end);
}
