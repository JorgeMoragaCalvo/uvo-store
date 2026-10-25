package org.uvo.uvostore.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.outbox.enums.OutboxChannel;
import org.uvo.uvostore.service.outbox.OutboxResult;

/**
 * PROD-15b. Los contadores de negocio: cosas que pasan, no cosas que están pendientes (eso es
 * {@link OperationalBacklogMetrics}).
 *
 * <p><b>Ninguna métrica lleva la tienda como etiqueta.</b> Con una serie por tienda, cada tienda nueva multiplica las
 * series de Prometheus, y esa cardinalidad crece con el negocio en vez de con el código. Para saber de qué tienda
 * se trata están los logs ({@code store=} en cada línea) y los avisos de Sentry.
 *
 * <p>Todas las combinaciones se registran al arrancar, en cero. Así la serie existe antes del primer evento, y una
 * alerta del tipo "subió en la última hora" no queda sin datos hasta que algo falla.
 */
@Component
public class BusinessMetrics {

    public static final String STOCK_DIVERGENCES = "uvostore.pos.stock.divergences";
    public static final String OUTBOX_DELIVERIES = "uvostore.outbox.deliveries";

    /** De dónde salió una divergencia de stock con el POS. */
    public enum DivergenceSource {
        /** La conciliación periódica corrigió el stock de un producto mapeado. */
        RECONCILIATION_STOCK,
        /** La conciliación corrigió nombre, precio o estado. */
        RECONCILIATION_CATALOG,
        /** El POS no devolvió un producto que tenemos mapeado. */
        RECONCILIATION_MISSING,
        /** Un webhook de stock del POS que no cuadra con lo que tenemos. */
        WEBHOOK,
        /** El stock que devuelve el POS al registrar una venta difiere del nuestro por encima del umbral. */
        ORDER_RESPONSE
    }

    private final Map<DivergenceSource, Counter> divergences = new EnumMap<>(DivergenceSource.class);
    private final Map<OutboxChannel, Map<OutboxResult.Outcome, Counter>> deliveries = new EnumMap<>(OutboxChannel.class);

    public BusinessMetrics(MeterRegistry registry) {
        for (DivergenceSource source : DivergenceSource.values()) {
            divergences.put(source, Counter.builder(STOCK_DIVERGENCES)
                    .description("Divergencias de stock o catálogo con el POS, por origen")
                    .tag("source", tag(source))
                    .register(registry));
        }
        for (OutboxChannel channel : OutboxChannel.values()) {
            Map<OutboxResult.Outcome, Counter> byOutcome = new EnumMap<>(OutboxResult.Outcome.class);
            for (OutboxResult.Outcome outcome : OutboxResult.Outcome.values()) {
                byOutcome.put(outcome, Counter.builder(OUTBOX_DELIVERIES)
                        .description("Intentos de entrega del outbox, por canal y resultado")
                        .tag("channel", tag(channel))
                        .tag("outcome", tag(outcome))
                        .register(registry));
            }
            deliveries.put(channel, byOutcome);
        }
    }

    public void stockDivergences(DivergenceSource source, int count) {
        if (count > 0) {
            divergences.get(source).increment(count);
        }
    }

    public void outboxDelivery(OutboxChannel channel, OutboxResult.Outcome outcome) {
        deliveries.get(channel).get(outcome).increment();
    }

    static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
