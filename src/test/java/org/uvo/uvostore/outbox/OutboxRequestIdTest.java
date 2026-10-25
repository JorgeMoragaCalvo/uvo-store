package org.uvo.uvostore.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.uvo.uvostore.config.LogContext;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.outbox.OutboxMessage;
import org.uvo.uvostore.entity.outbox.enums.OutboxType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.service.customer.CustomerAccountsPolicy;
import org.uvo.uvostore.service.outbox.OutboxService;
import org.uvo.uvostore.service.pos.PosOrderNotifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROD-15b. Cada fila del outbox guarda el id de quien la encoló: es lo que después viaja al POS con la venta
 * ({@code OutboxDispatcherTest} y {@code PosClientRequestIdTest} cubren las otras dos mitades).
 */
class OutboxRequestIdTest {

    private final OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
    private final PosOrderNotifier posNotifier = mock(PosOrderNotifier.class);
    private final OutboxService service = new OutboxService(repository, posNotifier, mock(CustomerAccountsPolicy.class));

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("La venta al POS y el correo de un pago confirmado guardan el id de la petición que lo confirmó")
    void theRowsKeepTheIdOfTheRequestThatConfirmedThePayment() {
        when(posNotifier.companiesToNotify(any())).thenReturn(List.of(77L));
        LogContext.putRequestId("it-webhook-15b-0001");

        service.enqueuePaymentConfirmed(order());

        assertThat(saved()).extracting(OutboxMessage::getType, OutboxMessage::getRequestId).containsExactly(
                org.assertj.core.groups.Tuple.tuple(OutboxType.POS_ORDER, "it-webhook-15b-0001"),
                org.assertj.core.groups.Tuple.tuple(OutboxType.ORDER_CONFIRMATION_EMAIL, "it-webhook-15b-0001"));
    }

    @Test
    @DisplayName("Si lo confirmó la conciliación, la fila lleva el id de esa pasada; sin ninguno, queda vacía")
    void aJobRunOrNothing() {
        LogContext.putRequestId("job-0123456789ab");
        service.enqueueOrderPlaced(order(), null);
        MDC.clear();
        service.enqueueOrderPlaced(order(), null);

        assertThat(saved()).extracting(OutboxMessage::getRequestId).containsExactly("job-0123456789ab", null);
    }

    private List<OutboxMessage> saved() {
        ArgumentCaptor<OutboxMessage> rows = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository, atLeastOnce()).save(rows.capture());
        return rows.getAllValues();
    }

    private static Order order() {
        Store store = Store.builder().name("Tienda").slug("tienda").build();
        store.setId(1L);
        Order order = new Order();
        order.setId(System.nanoTime());
        order.setOrderNumber("ORD-15B");
        order.setStore(store);
        return order;
    }
}
