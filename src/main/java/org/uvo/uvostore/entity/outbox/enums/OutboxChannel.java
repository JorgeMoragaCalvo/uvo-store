package org.uvo.uvostore.entity.outbox.enums;

/**
 * PROD-13b. Cada canal tiene su propio despacho, como mamparo: un POS que no contesta no puede dejar sin
 * salir los correos, y al revés. Es lo que hacían los dos ejecutores separados de R1, ahora con dos
 * trabajos programados en vez de dos colas en memoria.
 */
public enum OutboxChannel {
    POS,
    MAIL,
    /**
     * IMP-06. Los correos de acceso del panel (invitaciones y restablecimiento de contraseña). Aparte de {@link #MAIL}
     * para que una tanda grande de correos de pedidos no retrase un enlace que vence en una hora.
     */
    ACCESS
}
