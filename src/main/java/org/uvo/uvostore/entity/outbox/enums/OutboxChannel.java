package org.uvo.uvostore.entity.outbox.enums;

/**
 * PROD-13b. Cada canal tiene su propio despacho, como mamparo: un POS que no contesta no puede dejar sin
 * salir los correos, y al revés. Es lo que hacían los dos ejecutores separados de R1, ahora con dos
 * trabajos programados en vez de dos colas en memoria.
 */
public enum OutboxChannel {
    POS,
    MAIL
}
