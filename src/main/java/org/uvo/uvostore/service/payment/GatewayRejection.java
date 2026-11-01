package org.uvo.uvostore.service.payment;

import java.util.Set;

/**
 * GAP-01. Cuándo un error de la pasarela es un «no» y cuándo un «no sé».
 *
 * <p>{@code RefundService} libera el saldo solo ante un rechazo definitivo —un {@code BusinessException}—; cualquier
 * otro error deja la intención PENDING y el importe reservado. Antes, un timeout o una conexión cortada se trataban
 * igual que un rechazo: si la pasarela sí había devuelto el dinero, el saldo quedaba libre y un segundo reembolso
 * con otro importe se autorizaba sobre dinero que ya había salido.
 *
 * <p>Por eso cada pasarela traduce a {@code BusinessException} solo lo que es inequívoco, y una respuesta 4xx lo es:
 * dice que la petición no se ejecutó. Un 5xx, un timeout o un error de red no lo dicen.
 */
public final class GatewayRejection {

    // 4xx que no garantizan que la operación no exista: 408 (la pasarela cortó a mitad de la petición), 409 (otra
    // petición con la misma clave de idempotencia sigue en curso), 423 (el recurso está tomado por otra operación) y
    // 424 (falló un servicio del que depende la pasarela). En los cuatro, la devolución puede terminar igual.
    private static final Set<Integer> UNCERTAIN_CLIENT_ERRORS = Set.of(408, 409, 423, 424);

    private GatewayRejection() {
    }

    /** {@code true} si este código HTTP de la pasarela asegura que la devolución no se hizo. */
    public static boolean isDefinitive(int httpStatus) {
        return httpStatus >= 400 && httpStatus < 500 && !UNCERTAIN_CLIENT_ERRORS.contains(httpStatus);
    }
}
