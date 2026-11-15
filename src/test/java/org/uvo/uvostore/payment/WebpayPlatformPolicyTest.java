package org.uvo.uvostore.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.config.AppEnvironment;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.payment.WebpayPlatformPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IMP-02. Qué significa {@code WEBPAY_ENABLED} en cada ambiente. Que staging y production rechacen el valor vacío
 * lo prueba {@code ProductionConfigurationRulesTest}; aquí, lo que la política resuelve cuando el arranque sigue.
 */
class WebpayPlatformPolicyTest {

    @Test
    @DisplayName("Vacío vale activado solo en development")
    void blankMeansEnabledOnlyInDevelopment() {
        assertThat(WebpayPlatformPolicy.resolve("", AppEnvironment.DEVELOPMENT)).isTrue();
        assertThat(WebpayPlatformPolicy.resolve(null, AppEnvironment.DEVELOPMENT)).isTrue();
        assertThat(WebpayPlatformPolicy.resolve(" ", AppEnvironment.STAGING)).isFalse();
        assertThat(WebpayPlatformPolicy.resolve("", AppEnvironment.PRODUCTION)).isFalse();
    }

    @Test
    @DisplayName("Un valor escrito manda en cualquier ambiente")
    void anExplicitValueWinsEverywhere() {
        for (AppEnvironment environment : AppEnvironment.values()) {
            assertThat(WebpayPlatformPolicy.resolve("true", environment)).isTrue();
            assertThat(WebpayPlatformPolicy.resolve(" FALSE ", environment)).isFalse();
        }
        assertThat(new WebpayPlatformPolicy("false", "development").isEnabled()).isFalse();
        assertThat(new WebpayPlatformPolicy("", "development").isEnabled()).isTrue();
        assertThat(new WebpayPlatformPolicy("true", "production").isEnabled()).isTrue();
    }

    @Test
    @DisplayName("Un valor ambiguo impide el arranque también en development")
    void anAmbiguousValueFails() {
        for (String value : new String[] {"yes", "on", "1", "si"}) {
            assertThatThrownBy(() -> new WebpayPlatformPolicy(value, "development"))
                    .as("WEBPAY_ENABLED='%s'", value)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("WEBPAY_ENABLED")
                    .hasMessageContaining("'" + value + "'");
        }
    }

    @Test
    @DisplayName("Apagado, requireEnabled es un 400 con el motivo para el comerciante")
    void requireEnabledExplainsWhy() {
        assertThatThrownBy(() -> WebpayPlatformPolicy.of(false).requireEnabled())
                .isInstanceOf(BusinessException.class)
                .hasMessage(WebpayPlatformPolicy.UNAVAILABLE_REASON);
        assertThatCode(() -> WebpayPlatformPolicy.of(true).requireEnabled()).doesNotThrowAnyException();
    }
}
