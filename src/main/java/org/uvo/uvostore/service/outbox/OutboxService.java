package org.uvo.uvostore.service.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.config.LogContext;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxStatus;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.service.customer.CustomerAccountsPolicy;
import org.uvo.uvostore.service.pos.PosOrderNotifier;
import org.uvo.uvostore.service.pos.PosOrderPayloadMapper;

import java.time.Instant;

/**
 * PROD-13b. Escribe los efectos de una orden en el outbox, <b>dentro de la transacción que los origina</b>
 * ({@code MANDATORY}): si esa transacción se deshace, el efecto también, y si se confirma, sobrevive a
 * cualquier caída posterior. Fuera de una transacción esto no tendría sentido, así que se niega.
 *
 * <p>Cada fila es un INSERT simple contra un {@code dedup_key} único. No se comprueba antes ni se usa
 * {@code ON CONFLICT}: si dos transacciones intentan encolar lo mismo, la segunda debe fallar entera, que es
 * lo que impide que un mismo pago produzca dos ventas en el POS.
 */
@Service
public class OutboxService {

    private final OutboxMessageRepository repository;
    private final PosOrderNotifier posNotifier;
    private final CustomerAccountsPolicy accountsPolicy;

    public OutboxService(OutboxMessageRepository repository, PosOrderNotifier posNotifier,
                         CustomerAccountsPolicy accountsPolicy) {
        this.repository = repository;
        this.posNotifier = posNotifier;
        this.accountsPolicy = accountsPolicy;
    }

    /**
     * Lo que sigue a crear una orden. Lo llama el checkout solo para una orden nueva: la reutilización de un
     * reintento (F15) no vuelve a encolar, o el comprador recibiría dos acuses.
     *
     * <p>F07: de aquí no cuelga nada que dependa del pago —ni la venta al POS, que emite un documento, ni el
     * correo de compra—: el pedido se ha hecho, no se ha pagado.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueOrderPlaced(Order order, Customer customer) {
        enqueue(order, OutboxType.ORDER_PLACED_EMAIL, null, "email:placed:" + order.getId());
        // F24 / PROD-07: la invitación solo existe con las cuentas encendidas y para un invitado que sigue
        // sin cuenta. Se vuelve a comprobar al enviarla (OrderEmailComposer.customerInvitation).
        if (accountsPolicy.isEnabled() && customer != null
                && customer.getInvitationToken() != null && customer.getPassword() == null) {
            enqueue(order, OutboxType.CUSTOMER_INVITATION_EMAIL, null, "email:invitation:" + order.getId());
        }
    }

    /**
     * Lo que sigue a un pago confirmado. Lo llama {@code OrderStatusServiceImpl.markPaid} justo después de
     * pasar la orden a PAID, y nunca en sus salidas tempranas (ya pagada, descuadre de monto): una orden que
     * no se cobró no registra ninguna venta ni da las gracias por una compra.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueuePaymentConfirmed(Order order) {
        // Primero la venta al POS: es el maestro del stock (PROD-13b, "orden interno").
        for (Long companyId : posNotifier.companiesToNotify(order)) {
            enqueue(order, OutboxType.POS_ORDER, companyId,
                    "pos:" + PosOrderPayloadMapper.idempotencyKey(order.getOrderNumber(), companyId));
        }
        enqueue(order, OutboxType.ORDER_CONFIRMATION_EMAIL, null, "email:confirmation:" + order.getId());
    }

    private void enqueue(Order order, OutboxType type, Long companyId, String dedupKey) {
        Instant now = Instant.now();
        repository.save(OutboxMessage.builder()
                .order(order)
                .type(type)
                .companyId(companyId)
                .dedupKey(dedupKey)
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                // PROD-15b. Quien encola: el retorno o el webhook del pago, el panel, o la pasada de la
                // conciliación ("job-…"). Viaja al POS con la venta.
                .requestId(LogContext.requestId())
                .build());
    }
}
