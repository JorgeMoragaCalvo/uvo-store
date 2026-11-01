package org.uvo.uvostore.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uvo.uvostore.service.payment.GatewayRejection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-01. La regla que decide si un reembolso fallido libera el saldo. Equivocarse hacia "definitivo" es lo
 * peligroso: libera un importe que la pasarela pudo haber devuelto. Es también la única prueba del lado de Stripe,
 * cuyo {@code Refund.create} es estático y no se puede sustituir.
 */
class GatewayRejectionTest {

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402, 403, 404, 422, 429})
    @DisplayName("Un 4xx asegura que la devolución no se hizo")
    void aClientErrorIsDefinitive(int status) {
        assertThat(GatewayRejection.isDefinitive(status)).isTrue();
    }

    @ParameterizedTest
    // 0 es lo que trae ApiConnectionException de Stripe: no hubo respuesta.
    @ValueSource(ints = {0, 200, 302, 408, 409, 423, 424, 500, 502, 503, 504})
    @DisplayName("Un 5xx, la falta de respuesta o un 4xx de operación en curso no aseguran nada")
    void anythingElseIsUncertain(int status) {
        assertThat(GatewayRejection.isDefinitive(status)).isFalse();
    }
}
