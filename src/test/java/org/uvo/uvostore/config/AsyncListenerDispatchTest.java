package org.uvo.uvostore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.uvo.uvostore.service.order.event.OrderConfirmationEmailListener;
import org.uvo.uvostore.service.order.event.OrderCompletedEvent;
import org.uvo.uvostore.service.order.event.PaymentConfirmedEvent;
import org.uvo.uvostore.service.order.event.PosNotificationListener;
import org.uvo.uvostore.service.order.event.StockDecrementListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1. Qué corre en el hilo de la petición y qué no. Es una decisión de diseño con dos mitades, y las
 * dos se rompen en silencio:
 *
 * <ul>
 *   <li>Quitarle el {@code @Async} al correo o al POS devuelve una llamada de red al hilo de la
 *       petición. El síntoma no aparece hasta que el tercero se cae, y entonces el que deja de
 *       responder es el sitio entero.</li>
 *   <li>Ponerle {@code @Async} al descuento de stock parece la misma mejora y es un fallo distinto:
 *       abre una ventana entre "pago confirmado" y "stock descontado" en la que dos compras del
 *       último artículo se cruzan y las dos quedan vendidas.</li>
 * </ul>
 *
 * <p>Se comprueba sobre las anotaciones y no arrancando un contexto porque lo que importa es
 * exactamente eso: en qué executor queda declarado cada listener. Un test de comportamiento con
 * hilos aquí sería más frágil y diría menos.
 */
class AsyncListenerDispatchTest {

    @Test
    @DisplayName("La notificación al POS sale del hilo de la petición, por su propio executor")
    void thePosNotificationRunsOnThePosExecutor() throws Exception {
        Async async = listenerMethod(PosNotificationListener.class, "onOrderCompleted", OrderCompletedEvent.class)
                .getAnnotation(Async.class);

        assertThat(async).as("notificar al POS es una llamada de red: no puede ir en el hilo de la petición").isNotNull();
        assertThat(async.value()).isEqualTo(AsyncConfig.POS_EXECUTOR);
    }

    @Test
    @DisplayName("El correo de confirmación sale del hilo de la petición, por un executor distinto")
    void theConfirmationEmailRunsOnTheMailExecutor() throws Exception {
        Async async = listenerMethod(OrderConfirmationEmailListener.class, "onOrderCompleted", OrderCompletedEvent.class)
                .getAnnotation(Async.class);

        assertThat(async).as("el SMTP es el que puede colgarse sin lanzar nada").isNotNull();
        // Executor propio y no el del POS: es un mamparo. Si comparten pool, un POS caído que llene la
        // cola deja al correo sin hilos, y un fallo se convierte en dos.
        assertThat(async.value()).isEqualTo(AsyncConfig.MAIL_EXECUTOR);
        assertThat(AsyncConfig.MAIL_EXECUTOR).isNotEqualTo(AsyncConfig.POS_EXECUTOR);
    }

    @Test
    @DisplayName("El descuento de stock NO es asíncrono, y eso es lo que evita la sobreventa")
    void theStockDecrementStaysOnTheRequestThread() throws Exception {
        Method method = listenerMethod(StockDecrementListener.class, "onPaymentConfirmed", PaymentConfirmedEvent.class);

        assertThat(method.getAnnotation(Async.class))
                .as("solo toca la base y es rápido; moverlo abre la ventana en la que dos compras del "
                        + "último artículo se cruzan")
                .isNull();
    }

    private Method listenerMethod(Class<?> type, String name, Class<?> eventType) throws Exception {
        return type.getDeclaredMethod(name, eventType);
    }
}
