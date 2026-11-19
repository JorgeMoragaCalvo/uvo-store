package org.uvo.uvostore.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.config.AppEnvironment;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.payment.PaymentPlatformPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IMP-02 / IMP-07. Qué significan {@code WEBPAY_ENABLED}, {@code STRIPE_ENABLED} y {@code MERCADOPAGO_ENABLED} en cada
 * ambiente. Que staging y production rechacen el valor vacío lo prueba {@code ProductionConfigurationRulesTest}; aquí,
 * lo que la política resuelve cuando el arranque sigue.
 */
class PaymentPlatformPolicyTest {

    @Test
    @DisplayName("Vacío vale activado solo en development, para las tres pasarelas")
    void blankMeansEnabledOnlyInDevelopment() {
        for (PaymentGatewayType gateway : PaymentGatewayType.values()) {
            assertThat(PaymentPlatformPolicy.resolve(gateway, "", AppEnvironment.DEVELOPMENT)).isTrue();
            assertThat(PaymentPlatformPolicy.resolve(gateway, null, AppEnvironment.DEVELOPMENT)).isTrue();
            assertThat(PaymentPlatformPolicy.resolve(gateway, " ", AppEnvironment.STAGING)).isFalse();
            assertThat(PaymentPlatformPolicy.resolve(gateway, "", AppEnvironment.PRODUCTION)).isFalse();
        }
        PaymentPlatformPolicy development = new PaymentPlatformPolicy("", "", "", "development");
        for (PaymentGatewayType gateway : PaymentGatewayType.values()) {
            assertThat(development.isEnabled(gateway)).as("%s", gateway).isTrue();
        }
    }

    @Test
    @DisplayName("Un valor escrito manda en cualquier ambiente, y cada pasarela lee el suyo")
    void anExplicitValueWinsEverywhere() {
        for (AppEnvironment environment : AppEnvironment.values()) {
            assertThat(PaymentPlatformPolicy.resolve(PaymentGatewayType.STRIPE, "true", environment)).isTrue();
            assertThat(PaymentPlatformPolicy.resolve(PaymentGatewayType.STRIPE, " FALSE ", environment)).isFalse();
        }
        // Constructor: (webpay, stripe, mercadopago, ambiente).
        PaymentPlatformPolicy policy = new PaymentPlatformPolicy("true", "false", "true", "production");
        assertThat(policy.isEnabled(PaymentGatewayType.WEBPAY)).isTrue();
        assertThat(policy.isEnabled(PaymentGatewayType.STRIPE)).isFalse();
        assertThat(policy.isEnabled(PaymentGatewayType.MERCADOPAGO)).isTrue();
    }

    @Test
    @DisplayName("Un valor ambiguo impide el arranque también en development, y el error nombra la variable")
    void anAmbiguousValueFails() {
        for (String value : new String[] {"yes", "on", "1", "si"}) {
            assertThatThrownBy(() -> new PaymentPlatformPolicy(value, "", "", "development"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("WEBPAY_ENABLED")
                    .hasMessageContaining("'" + value + "'");
            assertThatThrownBy(() -> new PaymentPlatformPolicy("", value, "", "development"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("STRIPE_ENABLED");
            assertThatThrownBy(() -> new PaymentPlatformPolicy("", "", value, "development"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MERCADOPAGO_ENABLED");
        }
    }

    @Test
    @DisplayName("Apagada, requireEnabled es un 400 con el motivo para el comerciante")
    void requireEnabledExplainsWhy() {
        assertThatThrownBy(() -> PaymentPlatformPolicy.of(false).requireEnabled(PaymentGatewayType.MERCADOPAGO))
                .isInstanceOf(BusinessException.class)
                .hasMessage(PaymentPlatformPolicy.unavailableReason(PaymentGatewayType.MERCADOPAGO))
                .hasMessageStartingWith("MercadoPago no está disponible");
        assertThatCode(() -> PaymentPlatformPolicy.of(true).requireEnabled(PaymentGatewayType.WEBPAY))
                .doesNotThrowAnyException();
        PaymentPlatformPolicy onlyWebpay = PaymentPlatformPolicy.only(PaymentGatewayType.WEBPAY);
        assertThat(onlyWebpay.isEnabled(PaymentGatewayType.WEBPAY)).isTrue();
        assertThat(onlyWebpay.isEnabled(PaymentGatewayType.STRIPE)).isFalse();
    }
}
