package org.uvo.uvostore.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.tenant.Store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByOrderNumber(String orderNumber);
    Optional<Order> findByStripeCheckoutSessionId(String sessionId);
    Optional<Order> findByStripePaymentIntentId(String paymentIntentId);
    Optional<Order> findByPosOrderId(String posOrderId);
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

    /**
     * R1. Solo la tienda de una orden, para el trabajo que corre en un hilo de pool. {@code Order.store}
     * es LAZY, así que leerlo desde {@code findById} fuera de transacción revienta; y abrir una
     * transacción alrededor mantendría una conexión de Hikari tomada durante toda la llamada de red que
     * viene después, que es justo la forma de agotar el pool que esto quiere evitar. Una consulta y se
     * cierra.
     */
    @Query("select o.store from Order o where o.id = :id")
    Optional<Store> findStoreByOrderId(@Param("id") Long id);

    List<Order> findByStatus(OrderStatus status); // Order::byStatus()
    List<Order> findByPaymentStatus(PaymentStatus paymentStatus); // Order::byPaymentStatus()
    List<Order> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<Order> findTop10ByOrderByCreatedAtDesc(); // Order::recent()
    List<Order> findByStatusAndCreatedAtAfter(OrderStatus status, Instant since);
    long countByPosSyncedFalse();

    // G2: órdenes que intentaron notificarse al POS y no lo consiguieron. El `syncAttempts >= 1` no
    // es decorativo — deja fuera a las órdenes que nunca tuvieron nada que notificar (ninguna línea
    // con ProductSyncMapping), que también tienen posSynced=false y reintentarlas no haría nada.
    // El `join fetch o.store` no es una optimización: quien consume esto es un job, fuera de toda
    // petición y por tanto sin sesión abierta, y lo primero que hace con cada orden es leer su
    // tienda para fijar el tenant. Sin el fetch, eso es un LazyInitializationException.
    @Query("""
            select o from Order o
            join fetch o.store
            where o.posSynced = false
              and o.syncAttempts >= 1
              and o.syncAttempts < :maxAttempts
              and o.createdAt < :notAfter
            order by o.createdAt asc
            """)
    List<Order> findPendingPosNotification(@Param("maxAttempts") int maxAttempts,
                                           @Param("notAfter") Instant notAfter,
                                           Pageable pageable);

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

    // Admin\Shipping\{Zones,Methods}\Index delete guards — refuse to delete a zone/method that
    // still has orders referencing it.
    long countByShippingZoneId(Long shippingZoneId);
    long countByShippingMethodRefId(Long shippingMethodId);

    // Admin\Reports\* — every report aggregates in-memory over the orders (+ items) in a date range.
    List<Order> findByCreatedAtBetween(Instant start, Instant end);
    List<Order> findByStoreIdAndCreatedAtBetween(Long storeId, Instant start, Instant end);
}
