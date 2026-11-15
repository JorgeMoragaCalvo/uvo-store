package org.uvo.uvostore.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PROD-08a. El arranque: con errores el constructor lanza —y el contexto no levanta—, con advertencias solo
 * registra. Como el resto de validaciones de arranque del proyecto ({@code SecretValidationTest}), se prueba el
 * constructor y no un contexto entero que no llegaría a subir.
 */
class ProductionConfigurationCheckTest {

    private final Logger checkLogger = (Logger) LoggerFactory.getLogger(ProductionConfigurationCheck.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        checkLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        checkLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("En producción con los defaults de desarrollo no arranca, y el mensaje trae todos los problemas")
    void productionWithTheDevelopmentDefaultsDoesNotStart() throws IOException {
        MockEnvironment properties = ProductionConfigurationRulesTest.shippedDefaults();
        properties.setProperty("app.environment", "production");

        assertThatThrownBy(() -> new ProductionConfigurationCheck(properties, name -> null,
                databaseWith(new DatabaseRole("uvostore", true, true, true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("La configuración no sirve para APP_ENVIRONMENT=production (9 problemas):")
                .hasMessageContaining("\n  - PUBLIC_STOREFRONT_URL")
                .hasMessageContaining("\n  - WEBPAY_ENABLED")
                .hasMessageContaining("\n  - MAIL_HOST")
                .hasMessageContaining("superusuario");
    }

    @Test
    @DisplayName("IMP-02: sin APP_ENVIRONMENT no arranca, en vez de caer a development")
    void withoutAppEnvironmentItDoesNotStart() throws IOException {
        ObjectProvider<JdbcTemplate> database = mock();

        assertThatThrownBy(() -> new ProductionConfigurationCheck(ProductionConfigurationRulesTest.shippedDefaults(),
                name -> null, database))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ENVIRONMENT no está definido");

        verifyNoInteractions(database);
    }

    @Test
    @DisplayName("En development arranca sin consultar la base, y el log dice el ambiente y si hay Webpay")
    void developmentStartsWithoutTouchingTheDatabase() throws IOException {
        ObjectProvider<JdbcTemplate> database = mock();
        MockEnvironment properties = ProductionConfigurationRulesTest.shippedDefaults();
        properties.setProperty("app.environment", "development");

        assertThatCode(() -> new ProductionConfigurationCheck(properties, name -> null, database))
                .doesNotThrowAnyException();

        verifyNoInteractions(database);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("APP_ENVIRONMENT=development")
                    .contains("no se aplican")
                    // WEBPAY_ENABLED vacío vale true solo en development.
                    .contains("Webpay activado");
        });
    }

    @Test
    @DisplayName("Las advertencias quedan en el log y el arranque sigue")
    void theWarningsAreLogged() {
        MockEnvironment properties = ProductionConfigurationRulesTest.production();
        properties.setProperty("app.environment", "production");
        properties.setProperty("app.pos.enabled", "false");

        assertThatCode(() -> new ProductionConfigurationCheck(properties,
                ProductionConfigurationRulesTest.sentry("production"),
                databaseWith(new DatabaseRole("uvostore_app", false, false, false))))
                .doesNotThrowAnyException();

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("POS_ENABLED=false");
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.INFO)
                .singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("APP_ENVIRONMENT=production")
                .contains("1 advertencias")
                .contains("Webpay activado");
    }

    @Test
    @DisplayName("IMP-02: producción sin Webpay arranca con las credenciales del sandbox y lo dice en el log")
    void productionWithoutWebpayStartsAndSaysSo() throws IOException {
        MockEnvironment shipped = ProductionConfigurationRulesTest.shippedDefaults();
        MockEnvironment properties = ProductionConfigurationRulesTest.production();
        properties.setProperty("app.environment", "production");
        properties.setProperty("webpay.enabled", "false");
        for (String key : new String[] {"webpay.environment", "webpay.parent-commerce-code", "webpay.api-key"}) {
            properties.setProperty(key, shipped.getProperty(key));
        }

        assertThatCode(() -> new ProductionConfigurationCheck(properties,
                ProductionConfigurationRulesTest.sentry("production"),
                databaseWith(new DatabaseRole("uvostore_app", false, false, false))))
                .doesNotThrowAnyException();

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.INFO)
                .singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("APP_ENVIRONMENT=production")
                .contains("Webpay desactivado");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JdbcTemplate> databaseWith(DatabaseRole role) {
        JdbcTemplate jdbc = mock();
        when(jdbc.queryForObject(anyString(), any(RowMapper.class))).thenReturn(role);
        ObjectProvider<JdbcTemplate> provider = mock();
        when(provider.getObject()).thenReturn(jdbc);
        return provider;
    }
}
