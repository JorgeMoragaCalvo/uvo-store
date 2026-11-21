package org.uvo.uvostore.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * IMP-09. El modo recuperación: lo que se enciende al levantar una base restaurada de un respaldo.
 *
 * <p>Un respaldo es una foto del pasado. Lo que pasó después —un cobro que Transbank ya confirmó, una venta que el POS
 * ya registró, un correo que ya salió— no está en la foto, y la instalación restaurada no lo sabe. Si arranca como
 * siempre, sus trabajos programados y su outbox se ponen a «terminar» lo que ven pendiente: reenvían al POS una venta
 * que ya tiene su documento, le mandan al comprador otro correo, cancelan por abandonada una orden que el comprador sí
 * pagó. Este modo existe para que la base restaurada se pueda mirar y comparar con los sistemas externos
 * ({@code RecoveryReportService}) <b>antes</b> de que haga nada.
 *
 * <p>Con el modo encendido:
 * <ul>
 *   <li>no corre ningún trabajo programado ({@link SchedulingConfig});</li>
 *   <li>no sale nada al POS ni al correo ({@code PosClient}, {@code EmailServiceImpl}): lanzan
 *       {@link RecoveryModeException};</li>
 *   <li>el checkout, los retornos y webhooks de pasarela, los webhooks del POS y toda escritura del panel responden
 *       503 ({@link RecoveryModeFilter}), salvo lo que sirve para conciliar.</li>
 * </ul>
 *
 * <p>Una sola propiedad y no las cinco banderas de los trabajos más el POS y el correo: en una restauración de
 * madrugada, olvidar una de siete es lo esperable. Se sale del modo cambiando el env y reiniciando; no hay un endpoint
 * para apagarlo, para que quede en el acta quién lo hizo y cuándo.
 */
@Component
public class RecoveryModePolicy {

    public static final String PROPERTY = "app.recovery-mode";

    private final boolean active;

    public RecoveryModePolicy(@Value("${" + PROPERTY + ":false}") boolean active) {
        this.active = active;
    }

    /** Para las clases que se construyen a mano en las pruebas, sin Spring. */
    public static RecoveryModePolicy inactive() {
        return new RecoveryModePolicy(false);
    }

    public boolean isActive() {
        return active;
    }

    /** Corta un efecto externo si el modo está encendido. {@code what} va en el mensaje: «la venta al POS». */
    public void requireInactive(String what) {
        if (active) {
            throw new RecoveryModeException(what);
        }
    }
}
