package org.uvo.uvostore.service.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.audit.enums.AuditAction;
import org.uvo.uvostore.entity.audit.enums.AuditTargetType;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderStatusHistory;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.repository.OrderStatusHistoryRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.audit.AuditDiff;
import org.uvo.uvostore.service.audit.AuditEntry;
import org.uvo.uvostore.service.audit.AuditTrail;

import java.time.Instant;
import java.util.NoSuchElementException;

/**
 * GAP-04. Lo que el panel hace con una entrega del outbox que falló de forma definitiva. Antes de esto la única salida
 * era el SQL a mano de V25.
 *
 * <p>Ninguna de las dos acciones entrega nada: reintentar devuelve la fila a la cola y la entrega el despachador en su
 * siguiente pasada, con el mismo handler, que vuelve a comprobar el estado de la orden (una venta de una orden ya
 * reembolsada se omite, igual que la primera vez). Las dos exigen que la fila siga {@code FAILED} en la misma
 * sentencia que la cambia; el despachador nunca escribe en una fila así, de modo que no compiten.
 *
 * <p>IMP-06. También los correos de acceso, que no tienen orden: no escriben historial y se auditan contra el usuario.
 * Reintentar uno vuelve a comprobar al entregarlo que su token sigue vigente y es el actual; si no, la fila queda
 * {@code SKIPPED} y la salida es reenviar la invitación o la recuperación de acceso del dueño, que emiten uno nuevo. La
 * API de plataforma ({@code PlatformAccessEmailController}) usa los métodos {@code *AccessEmail*}, sobre cualquier
 * tienda y solo para esos tipos: el dueño de una tienda nueva cuya invitación no salió no tiene panel donde verla.
 */
@Service
public class OutboxAdminService {

    private static final Logger log = LoggerFactory.getLogger(OutboxAdminService.class);

    static final String RESOLVED_PREFIX = "Resuelta a mano: ";

    private final OutboxMessageRepository repository;
    private final OrderStatusHistoryRepository historyRepository;
    private final AuditTrail auditTrail;

    public OutboxAdminService(OutboxMessageRepository repository, OrderStatusHistoryRepository historyRepository,
                              AuditTrail auditTrail) {
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public Page<OutboxFailureDto> listFailed(Pageable pageable) {
        return repository.findFailedForStore(TenantContext.requireStoreId(), pageable).map(OutboxAdminService::toDto);
    }

    @Transactional(readOnly = true)
    public long countFailed() {
        return repository.countFailedForStore(TenantContext.requireStoreId());
    }

    /**
     * Devuelve la fila a la cola. Una venta al POS de resultado incierto pudo haber llegado, y el POS todavía no
     * confirmó que respete la clave de idempotencia (POS-1): reenviarla sin mirar puede emitir un segundo documento
     * tributario, así que el operador tiene que decir que lo revisó.
     *
     * @param posChecked el operador comprobó en el POS que la venta no está
     */
    @Transactional
    public void retry(Long id, String note, boolean posChecked) {
        requireNote(note, "Reintentar un envío necesita una nota");
        requeue(failedOrThrow(repository.findForStore(id, TenantContext.requireStoreId()).orElse(null), id),
                note, posChecked);
    }

    /**
     * Cierra la fila sin reenviar, porque ya se resolvió por otra vía (la venta está en el POS, el correo se mandó a
     * mano). Una venta cerrada así deja de frenar la conciliación del stock con el POS
     * ({@code findProductsWithUnregisteredSales}), así que la nota tiene que poder explicarlo.
     */
    @Transactional
    public void resolve(Long id, String note) {
        requireNote(note, "Marcar un envío como resuelto necesita una nota");
        close(failedOrThrow(repository.findForStore(id, TenantContext.requireStoreId()).orElse(null), id), note);
    }

    /** IMP-06. Los correos de acceso fallidos de todas las tiendas, o de una si {@code storeId} no es nulo. */
    @Transactional(readOnly = true)
    public Page<OutboxFailureDto> listFailedAccessEmails(Long storeId, Pageable pageable) {
        return repository.findFailedAccessEmails(storeId, pageable).map(OutboxAdminService::toDto);
    }

    /** IMP-06. {@link #retry} para un correo de acceso de cualquier tienda; una fila de orden no se encuentra. */
    @Transactional
    public void retryAccessEmail(Long id, String note) {
        requireNote(note, "Reintentar un envío necesita una nota");
        requeue(failedOrThrow(repository.findAccessEmail(id).orElse(null), id), note, false);
    }

    /** IMP-06. {@link #resolve} para un correo de acceso de cualquier tienda. */
    @Transactional
    public void resolveAccessEmail(Long id, String note) {
        requireNote(note, "Marcar un envío como resuelto necesita una nota");
        close(failedOrThrow(repository.findAccessEmail(id).orElse(null), id), note);
    }

    private void requeue(OutboxMessage message, String note, boolean posChecked) {
        if (isLegacy(message)) {
            throw new BusinessException("Este envío es anterior al outbox y no indica a qué empresa del POS va: "
                    + "solo puede marcarse resuelto");
        }
        if (isUncertain(message) && !posChecked) {
            throw new BusinessException("Esta venta pudo haber llegado al POS. Confirma que revisaste en el POS que no "
                    + "está antes de reintentarla");
        }
        if (repository.requeue(message.getId(), Instant.now()) != 1) {
            throw alreadyHandled();
        }

        appendHistory(message, label(message.getType()) + ": reintento pedido desde el panel. Nota: " + note.trim());
        auditTrail.record(audit(AuditAction.OUTBOX_RETRY, message)
                .details(details(message).with("posChecked", posChecked)));
        log.info("Envío del outbox reencolado a mano id={} tipo={} {}", message.getId(), message.getType(),
                subject(message));
    }

    private void close(OutboxMessage message, String note) {
        if (repository.resolveManually(message.getId(), RESOLVED_PREFIX + note.trim(), Instant.now()) != 1) {
            throw alreadyHandled();
        }

        appendHistory(message, label(message.getType()) + ": marcado como resuelto desde el panel. Nota: "
                + note.trim());
        auditTrail.record(audit(AuditAction.OUTBOX_RESOLVE, message).details(details(message)));
        log.info("Envío del outbox resuelto a mano id={} tipo={} {}", message.getId(), message.getType(),
                subject(message));
    }

    private static OutboxMessage failedOrThrow(OutboxMessage message, Long id) {
        if (message == null) {
            throw new NoSuchElementException("Outbox message " + id + " not found");
        }
        if (message.getStatus() != OutboxStatus.FAILED) {
            throw alreadyHandled();
        }
        return message;
    }

    private static BusinessException alreadyHandled() {
        return new BusinessException("Este envío ya no está fallido: otra persona lo reintentó o lo resolvió");
    }

    private static void requireNote(String note, String message) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(message);
        }
    }

    /** Las filas de antes del outbox (V25) no tienen empresa, y su CHECK no deja volverlas a {@code PENDING}. */
    static boolean isLegacy(OutboxMessage message) {
        return message.getType() == OutboxType.POS_ORDER && message.getCompanyId() == null;
    }

    /**
     * Una venta al POS que no fue rechazada: o no hubo respuesta, o el proceso murió durante la llamada, o se agotó el
     * plazo reintentando algo de lo que tampoco se supo el resultado. Ante la duda, incierta.
     */
    static boolean isUncertain(OutboxMessage message) {
        return message.getType() == OutboxType.POS_ORDER
                && (message.getLastError() == null || !message.getLastError().startsWith(OutboxDispatcher.REJECTED_PREFIX));
    }

    /**
     * Contra la orden, o contra el usuario en un correo de acceso. Con la tienda de la fila dicha explícitamente: desde
     * la API de plataforma el Host de la llamada puede ser el de otra tienda.
     */
    private static AuditEntry audit(AuditAction action, OutboxMessage message) {
        AuditEntry entry = message.getOrder() != null
                ? AuditEntry.of(action, AuditTargetType.ORDER, message.getOrder().getId())
                : AuditEntry.of(action, AuditTargetType.USER, message.getUser().getId());
        return entry.forStore(message.getStore().getId());
    }

    private static AuditDiff details(OutboxMessage message) {
        AuditDiff diff = AuditDiff.create()
                .with("outboxId", message.getId())
                .with("type", message.getType())
                .with("attempts", message.getAttempts());
        if (message.getOrder() != null) {
            return diff.with("orderNumber", message.getOrder().getOrderNumber())
                    .with("companyId", message.getCompanyId());
        }
        // Ni el token ni su huella.
        return diff.with("email", message.getUser().getEmail());
    }

    private void appendHistory(OutboxMessage message, String notes) {
        Order order = message.getOrder();
        if (order == null) {
            return;
        }
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setStatus(order.getStatus().name());
        history.setNotes(notes);
        historyRepository.save(history);
    }

    private static String subject(OutboxMessage message) {
        return message.getOrder() != null ? "order_id=" + message.getOrder().getId()
                : "user_id=" + message.getUser().getId();
    }

    private static String label(OutboxType type) {
        return switch (type) {
            case POS_ORDER -> "Venta al POS";
            case ORDER_PLACED_EMAIL -> "Correo de pedido recibido";
            case ORDER_CONFIRMATION_EMAIL -> "Correo de confirmación de pago";
            case CUSTOMER_INVITATION_EMAIL -> "Correo de invitación a crear cuenta";
            case ADMIN_INVITATION_EMAIL -> "Correo de invitación al panel";
            case PASSWORD_RESET_EMAIL -> "Correo de restablecimiento de contraseña";
        };
    }

    private static OutboxFailureDto toDto(OutboxMessage message) {
        Order order = message.getOrder();
        boolean access = message.getUser() != null;
        return new OutboxFailureDto(message.getId(), message.getType(), message.getType().channel(),
                message.getStore().getId(),
                order == null ? null : order.getId(), order == null ? null : order.getOrderNumber(),
                message.getCompanyId(),
                access ? message.getUser().getId() : null, access ? message.getUser().getEmail() : null,
                message.getAttempts(), message.getLastError(), message.getCreatedAt(), message.getUpdatedAt(),
                isUncertain(message), isLegacy(message));
    }
}
