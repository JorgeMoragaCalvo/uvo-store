package org.uvo.uvostore.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * PROD-13b. Las consultas del despachador recorren todas las tiendas a propósito —es un trabajo
 * programado que fija el tenant fila a fila—, y {@code join fetch} de la orden y su tienda porque se
 * consumen fuera de toda sesión.
 *
 * <p>Los cambios de estado son UPDATE condicionados, y el número de filas afectadas es la única respuesta
 * de fiar (el mismo criterio que C5 con el stock): el reclamo exige {@code PENDING}, y cerrar un intento
 * exige que la fila siga {@code IN_FLIGHT} con el mismo número de intentos, para que un proceso que se
 * quedó atrás no pise lo que ya decidió otro.
 */
@Repository
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    List<OutboxMessage> findByOrderIdOrderByIdAsc(Long orderId);

    /**
     * POS-4. De estos productos, los que tienen una venta web que esta empresa del POS todavía no registró: su
     * fila {@code POS_ORDER} sigue pendiente, en vuelo o fallida. Nuestro stock ya la descontó y el del POS no,
     * así que la conciliación no debe corregirlos hacia el POS: desharía la venta.
     *
     * <p>{@code FAILED} cuenta igual: es una venta que el POS no tiene y que espera revisión manual (V25).
     * {@code SENT} y {@code SKIPPED} no: la primera ya está en el POS, y la segunda no se va a enviar.
     */
    @Query("""
            select distinct i.product.id from OrderItem i, OutboxMessage m
            where m.order = i.order
              and m.type = org.uvo.uvostore.entity.outbox.enums.OutboxType.POS_ORDER
              and m.companyId = :companyId
              and m.status in (org.uvo.uvostore.entity.outbox.enums.OutboxStatus.PENDING,
                               org.uvo.uvostore.entity.outbox.enums.OutboxStatus.IN_FLIGHT,
                               org.uvo.uvostore.entity.outbox.enums.OutboxStatus.FAILED)
              and i.product.id in :productIds
            """)
    List<Long> findProductsWithUnregisteredSales(@Param("companyId") Long companyId,
                                                 @Param("productIds") Collection<Long> productIds);

    @Query("""
            select m from OutboxMessage m
            join fetch m.order o
            join fetch o.store
            where m.type in :types
              and m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.PENDING
              and m.nextAttemptAt <= :now
            order by m.id asc
            """)
    List<OutboxMessage> findDue(@Param("types") Collection<OutboxType> types, @Param("now") Instant now,
                                Pageable pageable);

    /**
     * En vuelo desde antes de {@code cutoff}: más de lo que puede durar una llamada, así que el proceso que
     * la reclamó ya no está. Se cuenta por tiempo y no solo por el cerrojo consultivo, porque ese cerrojo se
     * suelta si se corta su conexión aunque la corrida siga viva.
     */
    @Query("""
            select m from OutboxMessage m
            join fetch m.order o
            join fetch o.store
            where m.type in :types
              and m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.IN_FLIGHT
              and m.updatedAt < :cutoff
            order by m.id asc
            """)
    List<OutboxMessage> findOrphans(@Param("types") Collection<OutboxType> types, @Param("cutoff") Instant cutoff,
                                    Pageable pageable);

    @Modifying
    @Query("""
            update OutboxMessage m
            set m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.IN_FLIGHT,
                m.attempts = m.attempts + 1,
                m.updatedAt = :now
            where m.id = :id
              and m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.PENDING
            """)
    int claim(@Param("id") Long id, @Param("now") Instant now);

    /** Cierre definitivo: {@code SENT}, {@code SKIPPED} o {@code FAILED}. */
    @Modifying
    @Query("""
            update OutboxMessage m
            set m.status = :status,
                m.lastError = :error,
                m.processedAt = :now,
                m.updatedAt = :now
            where m.id = :id
              and m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.IN_FLIGHT
              and m.attempts = :attempts
            """)
    int finish(@Param("id") Long id, @Param("attempts") int attempts, @Param("status") OutboxStatus status,
               @Param("error") String error, @Param("now") Instant now);

    /** De vuelta a la cola, para otro intento a partir de {@code nextAttemptAt}. */
    @Modifying
    @Query("""
            update OutboxMessage m
            set m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.PENDING,
                m.lastError = :error,
                m.nextAttemptAt = :nextAttemptAt,
                m.updatedAt = :now
            where m.id = :id
              and m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.IN_FLIGHT
              and m.attempts = :attempts
            """)
    int reschedule(@Param("id") Long id, @Param("attempts") int attempts, @Param("error") String error,
                   @Param("nextAttemptAt") Instant nextAttemptAt, @Param("now") Instant now);

    /**
     * PROD-23. Lo que el outbox todavía tiene que entregar por una tienda (la venta al POS, los correos de las
     * órdenes). Cerrar con algo aquí borraría la conexión con el POS antes de que la venta llegue.
     */
    @Query("""
            select count(m) from OutboxMessage m
            where m.order.store.id = :storeId
              and m.status in (org.uvo.uvostore.entity.outbox.enums.OutboxStatus.PENDING,
                               org.uvo.uvostore.entity.outbox.enums.OutboxStatus.IN_FLIGHT)
            """)
    long countUnfinishedForStore(@Param("storeId") Long storeId);

    /**
     * GAP-04. Las entregas de una tienda que fallaron de forma definitiva y esperan a una persona: la página «Envíos
     * fallidos» del panel. La fila no tiene tienda propia, así que el filtro va por su orden (y por eso este
     * repositorio no lo vigila {@code RepositoryTenantScopeTest}).
     */
    @Query(value = """
            select m from OutboxMessage m
            join fetch m.order o
            where o.store.id = :storeId
              and m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.FAILED
            order by m.updatedAt desc, m.id desc
            """,
            countQuery = """
            select count(m) from OutboxMessage m
            where m.order.store.id = :storeId
              and m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.FAILED
            """)
    Page<OutboxMessage> findFailedForStore(@Param("storeId") Long storeId, Pageable pageable);

    @Query("""
            select count(m) from OutboxMessage m
            where m.order.store.id = :storeId
              and m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.FAILED
            """)
    long countFailedForStore(@Param("storeId") Long storeId);

    @Query("""
            select m from OutboxMessage m
            join fetch m.order o
            where m.id = :id
              and o.store.id = :storeId
            """)
    Optional<OutboxMessage> findForStore(@Param("id") Long id, @Param("storeId") Long storeId);

    /**
     * GAP-04. Reintento desde el panel: de vuelta a la cola sin tocar {@code attempts}, que es lo que el despachador
     * compara al cerrar el intento. Exige {@code FAILED}, y como el despachador nunca escribe en una fila así, no hace
     * falta cerrojo: de dos operadores a la vez, uno recibe 1 y el otro 0.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update OutboxMessage m
            set m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.PENDING,
                m.nextAttemptAt = :now,
                m.requeuedAt = :now,
                m.updatedAt = :now
            where m.id = :id
              and m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.FAILED
            """)
    int requeue(@Param("id") Long id, @Param("now") Instant now);

    /** GAP-04. Cierre a mano, sin reenviar: ya se resolvió por otra vía. Mismo guard que {@link #requeue}. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update OutboxMessage m
            set m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.SKIPPED,
                m.lastError = :detail,
                m.processedAt = :now,
                m.updatedAt = :now
            where m.id = :id
              and m.status = org.uvo.uvostore.entity.outbox.enums.OutboxStatus.FAILED
            """)
    int resolveManually(@Param("id") Long id, @Param("detail") String detail, @Param("now") Instant now);

    /**
     * PROD-15b. Lo que el outbox tiene en cola ({@code PENDING}, {@code IN_FLIGHT}) y lo que falló de forma definitiva
     * ({@code FAILED}), en todas las tiendas, por tipo y estado, con la fila más vieja de cada grupo. Una sola
     * consulta para todos los gauges del outbox ({@code OperationalBacklogMetrics}); el canal sale del tipo.
     *
     * @return filas {@code [OutboxType, OutboxStatus, Long count, Instant oldestCreatedAt]}
     */
    @Query("""
            select m.type, m.status, count(m), min(m.createdAt) from OutboxMessage m
            where m.status in (org.uvo.uvostore.entity.outbox.enums.OutboxStatus.PENDING,
                               org.uvo.uvostore.entity.outbox.enums.OutboxStatus.IN_FLIGHT,
                               org.uvo.uvostore.entity.outbox.enums.OutboxStatus.FAILED)
            group by m.type, m.status
            """)
    List<Object[]> countBacklogByTypeAndStatus();
}
