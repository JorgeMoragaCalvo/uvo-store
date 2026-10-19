package org.uvo.uvostore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.uvo.uvostore.service.order.event.PaymentConfirmedEvent;
import org.uvo.uvostore.service.order.event.StockDecrementListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1. Qué corre en el hilo de la petición y qué no.
 *
 * <p>PROD-13b: de las dos mitades que fijaba esta clase queda una. Los efectos que salen a la red —la venta
 * al POS y los correos— ya no son oyentes {@code @Async}: se escriben en el outbox con el commit y los
 * entrega un despachador programado ({@code OutboxDispatcher}), fuera del hilo de la petición por
 * construcción.
 *
 * <p>Lo que sigue en pie es lo contrario: el descuento de stock se queda en el hilo de la petición, a
 * propósito. Ponerle {@code @Async} parece una mejora y es un fallo: abre una ventana entre "pago confirmado"
 * y "stock descontado" en la que dos compras del último artículo se cruzan y las dos quedan vendidas.
 *
 * <p>Se comprueba sobre la anotación y no arrancando un contexto porque lo que importa es exactamente eso.
 */
class AsyncListenerDispatchTest {

    @Test
    @DisplayName("El descuento de stock NO es asíncrono, y eso es lo que evita la sobreventa")
    void theStockDecrementStaysOnTheRequestThread() throws Exception {
        Method method = StockDecrementListener.class.getDeclaredMethod("onPaymentConfirmed", PaymentConfirmedEvent.class);

        assertThat(method.getAnnotation(Async.class))
                .as("solo toca la base y es rápido; moverlo abre la ventana en la que dos compras del "
                        + "último artículo se cruzan")
                .isNull();
    }
}
