package org.uvo.uvostore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-09. Qué corta el modo recuperación y qué deja pasar, ruta por ruta. El cableado (que el filtro está delante de
 * todo y responde 503) lo prueba {@code RecoveryModeTest}.
 */
class RecoveryModeFilterTest {

    @ParameterizedTest(name = "{0} {1} -> bloquea={2}")
    @DisplayName("Corta toda escritura y el retorno de Webpay; deja pasar lecturas y lo que sirve para conciliar")
    @CsvSource({
            // Efectos externos o escrituras de negocio: cortados.
            "POST, /api/v1/checkout, true",
            "POST, /api/v1/webpay/create, true",
            "GET, /api/v1/webpay/return, true",
            "POST, /api/v1/webpay/return, true",
            "POST, /api/v1/stripe/webhook, true",
            "POST, /api/v1/mercadopago/webhook, true",
            "POST, /api/v1/verify-payment, true",
            "POST, /api/webhooks/pos/stock-updated, true",
            "POST, /api/sync/product, true",
            "POST, /api/admin/orders/7/cancel, true",
            "POST, /api/admin/orders/7/refund, true",
            "PUT, /api/admin/orders/7/status, true",
            "POST, /api/admin/pos/resync, true",
            "POST, /api/admin/auth/forgot-password, true",
            "POST, /api/customer/auth/register, true",
            "POST, /api/platform/stores, true",
            "DELETE, /api/admin/coupons/3, true",
            // Lecturas: pasan.
            "GET, /api/v1/products, false",
            "GET, /api/admin/orders/7, false",
            "GET, /actuator/health/readiness, false",
            "HEAD, /api/v1/products, false",
            "OPTIONS, /api/v1/checkout, false",
            // Conciliación y acceso al panel: pasan.
            "POST, /api/admin/auth/login, false",
            "POST, /api/admin/auth/logout, false",
            "POST, /api/admin/outbox/12/retry, false",
            "POST, /api/admin/outbox/12/resolve, false",
            "POST, /api/admin/orders/7/refunds/3/resolve, false",
            "POST, /api/admin/orders/7/refund/external, false",
            "POST, /api/platform/access-emails/5/retry, false",
            "POST, /api/platform/recovery/outbox/hold, false",
            "POST, /api/v1/cart/calculate, false",
            // Lo permitido no se estira a rutas parecidas.
            "POST, /api/admin/outbox/12/retry/all, true",
            "POST, /api/admin/orders/7/refunds/3/resolve/x, true",
    })
    void blocks(String method, String path, boolean blocked) {
        assertThat(RecoveryModeFilter.blocks(method, path)).isEqualTo(blocked);
    }
}
