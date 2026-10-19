package org.uvo.uvostore.service.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;

/**
 * PROD-13b. Solo el reloj del despacho; el trabajo está en {@link OutboxDispatcher}.
 *
 * <p>Separados para poder apagar esto en los tests ({@code app.outbox.scheduler.enabled=false} en surefire,
 * {@code pom.xml}). A diferencia de los otros trabajos programados, que arrancan a los diez minutos, este corre
 * cada pocos segundos: encendido, cada contexto de Spring que los tests guardan en caché despacharía sobre la
 * base compartida y se quedaría con las filas que un test está comprobando.
 *
 * <p>Dos métodos y no uno: con el pool del planificador a un hilo por trabajo, un POS que no contesta ocupa el
 * suyo y los correos siguen saliendo por el otro.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private final OutboxDispatcher dispatcher;

    public OutboxScheduler(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${app.outbox.interval-ms:10000}",
            initialDelayString = "${app.outbox.interval-ms:10000}")
    public void dispatchPos() {
        dispatcher.dispatch(OutboxChannel.POS);
    }

    @Scheduled(fixedDelayString = "${app.outbox.interval-ms:10000}",
            initialDelayString = "${app.outbox.interval-ms:10000}")
    public void dispatchMail() {
        dispatcher.dispatch(OutboxChannel.MAIL);
    }
}
