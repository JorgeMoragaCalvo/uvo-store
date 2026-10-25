package org.uvo.uvostore.config;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.MDC;

/**
 * PROD-15b. Las dos claves del MDC que lleva cada línea de log ({@code logging.pattern.correlation}): el id de la
 * petición y la tienda. Viven juntas para que nadie escriba "storeID" en un sitio y "storeId" en otro y el patrón
 * se quede vacío sin que nada falle.
 *
 * <ul>
 *   <li>{@value #REQUEST_ID}: lo pone {@link RequestIdFilter} en una petición, el planificador en cada pasada de un
 *       job ({@link SchedulingConfig}, con {@link #newJobId()}) y {@code OutboxDispatcher} mientras entrega una fila
 *       (el id de la petición que la encoló).</li>
 *   <li>{@value #STORE_ID}: lo pone {@code TenantContext} (petición, jobs y outbox, vía {@code runWithin}) y los dos
 *       filtros de entrada del POS, que resuelven la tienda por la conexión y no por el Host.</li>
 * </ul>
 */
public final class LogContext {

    public static final String REQUEST_ID = "requestId";
    public static final String STORE_ID = "storeId";

    private LogContext() {
    }

    /** Pone la tienda, o la quita si es null: un hilo de pool no puede quedarse con la de la tarea anterior. */
    public static void putStore(Long storeId) {
        if (storeId == null) {
            MDC.remove(STORE_ID);
        } else {
            MDC.put(STORE_ID, storeId.toString());
        }
    }

    public static void removeStore() {
        MDC.remove(STORE_ID);
    }

    /** Pone el id de la petición, o lo quita si es null. */
    public static void putRequestId(String requestId) {
        if (requestId == null) {
            MDC.remove(REQUEST_ID);
        } else {
            MDC.put(REQUEST_ID, requestId);
        }
    }

    public static String requestId() {
        return MDC.get(REQUEST_ID);
    }

    /**
     * El id de una pasada de job: {@code job-} y 12 hexadecimales. Cumple el formato que acepta
     * {@link RequestIdFilter}, así que viaja al POS y a la auditoría igual que el de una petición, y el prefijo dice
     * de un vistazo que no hubo petición.
     */
    public static String newJobId() {
        byte[] random = new byte[6];
        ThreadLocalRandom.current().nextBytes(random);
        return "job-" + HexFormat.of().formatHex(random);
    }
}
