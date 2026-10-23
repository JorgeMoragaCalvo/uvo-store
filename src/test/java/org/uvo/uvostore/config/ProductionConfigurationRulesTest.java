package org.uvo.uvostore.config;

import cl.transbank.common.IntegrationApiKeys;
import cl.transbank.common.IntegrationCommerceCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.uvo.uvostore.config.ProductionConfigurationRules.Findings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PROD-08a. Qué configuración impide el arranque fuera de desarrollo y qué solo deja una advertencia.
 *
 * <p>JUnit puro: las reglas son una función de las propiedades, del entorno del proceso y del rol de la base. La
 * base de comparación es una configuración de producción completa, sin errores ni advertencias; cada caso le
 * cambia una sola cosa. Los defaults de desarrollo se leen del {@code application.properties} de verdad, para que
 * un default nuevo e inseguro no pase sin que nadie lo vea.
 */
class ProductionConfigurationRulesTest {

    private static final String UPLOAD_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "uvostore-subidas").toAbsolutePath().toString();
    private static final String SENTRY_DSN = "https://clave@sentry.example/1";
    private static final DatabaseRole APP_ROLE = new DatabaseRole("uvostore_app", false, false, false);

    @Test
    @DisplayName("Una configuración de producción completa no tiene errores ni advertencias")
    void aCompleteProductionConfigurationHasNoErrors() {
        Findings findings = check(AppEnvironment.PRODUCTION, production(), sentry("production"), APP_ROLE);

        assertThat(findings.errors()).isEmpty();
        assertThat(findings.warnings()).isEmpty();
    }

    static Stream<Arguments> developmentDefaults() {
        return Stream.of(
                row("PUBLIC_STOREFRONT_URL", "app.public-url.storefront", "http://{host}:5173"),
                row("PUBLIC_API_URL", "app.public-url.api", "http://{host}:8080"),
                row("PUBLIC_PLATFORM_DOMAIN", "app.public-url.platform-domain", "localhost"),
                row("PUBLIC_PLATFORM_DOMAIN", "app.public-url.platform-domain", "demo.localhost"),
                row("PUBLIC_PLATFORM_DOMAIN", "app.public-url.platform-domain", "192.168.1.10"),
                row("PUBLIC_PLATFORM_DOMAIN", "app.public-url.platform-domain", "intranet"),
                row("WEBPAY_ENVIRONMENT", "webpay.environment", "integration"),
                row("WEBPAY_ENVIRONMENT", "webpay.environment", "produccion"),
                row("WEBPAY_PARENT_COMMERCE_CODE", "webpay.parent-commerce-code", IntegrationCommerceCodes.WEBPAY_PLUS_MALL),
                row("WEBPAY_API_KEY", "webpay.api-key", IntegrationApiKeys.WEBPAY),
                row("APP_UPLOAD_DIR", "app.upload-dir", "uploads"),
                Arguments.of("APP_STORAGE_S3_BUCKET", Map.of("app.storage.driver", "s3", "app.storage.s3.bucket", "")),
                row("MAIL_HOST", "spring.mail.host", ""),
                row("SWAGGER_ENABLED", "app.swagger.enabled", "true"),
                row("SWAGGER_ENABLED", "app.swagger.enabled", "on"),
                row("APP_ENCRYPTION_KEY", "app.encryption-key", RequiredSecret.LEGACY_ENCRYPTION_KEY),
                row("FLYWAY_USER", "app.flyway.user", ""),
                row("FLYWAY_USER", "app.flyway.user", "uvostore_app"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("developmentDefaults")
    @DisplayName("Cada valor de desarrollo es un error en producción, y el mensaje nombra la variable")
    void eachDevelopmentDefaultIsAnError(String envVar, Map<String, String> overrides) {
        MockEnvironment properties = production();
        overrides.forEach(properties::setProperty);

        Findings findings = check(AppEnvironment.PRODUCTION, properties, sentry("production"), APP_ROLE);

        assertThat(findings.errors()).singleElement().asString().startsWith(envVar);
    }

    @Test
    @DisplayName("Con los defaults de application.properties, producción lista todos los errores juntos")
    void allErrorsAreReportedTogether() throws IOException {
        Findings findings = check(AppEnvironment.PRODUCTION, shippedDefaults(), sentry("production"),
                new DatabaseRole("uvostore", true, true, true));

        assertThat(findings.errors())
                .anySatisfy(error -> assertThat(error).startsWith("PUBLIC_STOREFRONT_URL"))
                .anySatisfy(error -> assertThat(error).startsWith("PUBLIC_API_URL"))
                .anySatisfy(error -> assertThat(error).startsWith("PUBLIC_PLATFORM_DOMAIN"))
                .anySatisfy(error -> assertThat(error).startsWith("WEBPAY_ENVIRONMENT"))
                .anySatisfy(error -> assertThat(error).startsWith("WEBPAY_PARENT_COMMERCE_CODE"))
                .anySatisfy(error -> assertThat(error).startsWith("WEBPAY_API_KEY"))
                .anySatisfy(error -> assertThat(error).startsWith("APP_UPLOAD_DIR"))
                .anySatisfy(error -> assertThat(error).startsWith("MAIL_HOST"))
                .anySatisfy(error -> assertThat(error).startsWith("SWAGGER_ENABLED"))
                .anySatisfy(error -> assertThat(error).startsWith("FLYWAY_USER"))
                .anySatisfy(error -> assertThat(error).contains("superusuario"))
                .hasSize(11);
    }

    @Test
    @DisplayName("Los defaults de Webpay son las credenciales del sandbox del SDK")
    void theWebpaySandboxDefaultsAreTheSdkOnes() throws IOException {
        // Si Transbank cambia sus credenciales de integración y application.properties no, la regla dejaría de
        // reconocer el sandbox. Esto lo avisa.
        MockEnvironment defaults = shippedDefaults();

        assertThat(defaults.getProperty("webpay.parent-commerce-code")).isEqualTo(IntegrationCommerceCodes.WEBPAY_PLUS_MALL);
        assertThat(defaults.getProperty("webpay.api-key")).isEqualTo(IntegrationApiKeys.WEBPAY);
        assertThat(defaults.getProperty("app.environment")).isEqualTo("development");
    }

    @Test
    @DisplayName("Staging permite el sandbox de Webpay y nada más")
    void stagingAllowsTheWebpaySandboxButNothingElse() {
        MockEnvironment sandbox = production();
        sandbox.setProperty("webpay.environment", "integration");
        sandbox.setProperty("webpay.parent-commerce-code", IntegrationCommerceCodes.WEBPAY_PLUS_MALL);
        sandbox.setProperty("webpay.api-key", IntegrationApiKeys.WEBPAY);

        assertThat(check(AppEnvironment.STAGING, sandbox, sentry("staging"), APP_ROLE).errors()).isEmpty();
        assertThat(check(AppEnvironment.PRODUCTION, sandbox, sentry("production"), APP_ROLE).errors()).hasSize(3);

        sandbox.setProperty("app.public-url.storefront", "http://{host}:5173");
        assertThat(check(AppEnvironment.STAGING, sandbox, sentry("staging"), APP_ROLE).errors())
                .singleElement().asString().startsWith("PUBLIC_STOREFRONT_URL");
    }

    @Test
    @DisplayName("Un APP_ENVIRONMENT mal escrito impide el arranque, en vez de caer a development")
    void aTypoInAppEnvironmentFails() {
        assertThatThrownBy(() -> AppEnvironment.parse("prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ENVIRONMENT")
                .hasMessageContaining("'prod'");
        assertThatThrownBy(() -> AppEnvironment.parse("produccion")).isInstanceOf(IllegalStateException.class);

        assertThat(AppEnvironment.parse(" Production ")).isEqualTo(AppEnvironment.PRODUCTION);
        assertThat(AppEnvironment.parse("staging")).isEqualTo(AppEnvironment.STAGING);
        assertThat(AppEnvironment.parse("")).isEqualTo(AppEnvironment.DEVELOPMENT);
        assertThat(AppEnvironment.parse(null)).isEqualTo(AppEnvironment.DEVELOPMENT);
    }

    @Test
    @DisplayName("En development no se comprueba nada y no se consulta la base")
    void developmentChecksNothingAndNeverQueriesTheDatabase() throws IOException {
        Supplier<DatabaseRole> untouchable = () -> {
            throw new AssertionError("en development no se consulta la base");
        };

        Findings findings = ProductionConfigurationRules.check(AppEnvironment.DEVELOPMENT, shippedDefaults(),
                name -> null, untouchable);

        assertThat(findings.errors()).isEmpty();
        assertThat(findings.warnings()).isEmpty();
    }

    @Test
    @DisplayName("Sin APP_ENVIRONMENT, una configuración con dominio real advierte que falta la señal")
    void aDeploymentLookingConfigWithoutAppEnvironmentWarns() {
        Findings findings = check(AppEnvironment.DEVELOPMENT, production(), name -> null, APP_ROLE);

        assertThat(findings.errors()).isEmpty();
        assertThat(findings.warnings()).singleElement().asString()
                .contains("uvostore.cl")
                .contains("APP_ENVIRONMENT");
    }

    @Test
    @DisplayName("Sentry: con DSN, el ambiente que informa tiene que ser el de APP_ENVIRONMENT")
    void sentryReportsUnderTheRightEnvironment() {
        assertThat(check(AppEnvironment.STAGING, production(), sentry(null), APP_ROLE).errors())
                .singleElement().asString()
                .startsWith("SENTRY_ENVIRONMENT")
                .contains("'production'")
                .contains("SENTRY_ENVIRONMENT=staging");
        assertThat(check(AppEnvironment.PRODUCTION, production(), sentry("staging"), APP_ROLE).errors())
                .singleElement().asString().startsWith("SENTRY_ENVIRONMENT");
        assertThat(check(AppEnvironment.STAGING, production(), sentry("staging"), APP_ROLE).errors()).isEmpty();

        Findings withoutDsn = check(AppEnvironment.PRODUCTION, production(), name -> null, APP_ROLE);
        assertThat(withoutDsn.errors()).isEmpty();
        assertThat(withoutDsn.warnings()).singleElement().asString().startsWith("SENTRY_DSN");
    }

    @Test
    @DisplayName("El rol de la aplicación no puede tener DDL de ninguna de las tres formas")
    void theApplicationRoleHasNoDdl() {
        assertThat(check(AppEnvironment.PRODUCTION, production(), sentry("production"),
                new DatabaseRole("uvostore_app", true, true, true)).errors())
                .singleElement().asString().startsWith("DB_USERNAME").contains("superusuario");
        assertThat(check(AppEnvironment.PRODUCTION, production(), sentry("production"),
                new DatabaseRole("uvostore_app", false, true, false)).errors())
                .singleElement().asString().contains("REVOKE CREATE ON SCHEMA");
        assertThat(check(AppEnvironment.PRODUCTION, production(), sentry("production"),
                new DatabaseRole("uvostore_app", false, false, true)).errors())
                .singleElement().asString().contains("REASSIGN OWNED BY uvostore_app");
    }

    @Test
    @DisplayName("Las advertencias no impiden el arranque")
    void theWarningsDoNotStopTheStartup() {
        MockEnvironment properties = production();
        properties.setProperty("app.pos.enabled", "false");
        properties.setProperty("app.rate-limit.trusted-proxies", "");

        Findings findings = check(AppEnvironment.PRODUCTION, properties, name -> null, APP_ROLE);

        assertThat(findings.errors()).isEmpty();
        assertThat(findings.warnings()).hasSize(3)
                .anySatisfy(warning -> assertThat(warning).startsWith("POS_ENABLED"))
                .anySatisfy(warning -> assertThat(warning).startsWith("RATE_LIMIT_TRUSTED_PROXIES"))
                .anySatisfy(warning -> assertThat(warning).startsWith("SENTRY_DSN"));
    }

    /** Todo lo que pide producción, bien puesto. Cada test le cambia una sola cosa. */
    static MockEnvironment production() {
        return new MockEnvironment()
                .withProperty("app.public-url.storefront", "https://{host}")
                .withProperty("app.public-url.api", "https://{host}")
                .withProperty("app.public-url.platform-domain", "uvostore.cl")
                .withProperty("webpay.environment", "production")
                .withProperty("webpay.parent-commerce-code", "597099999999")
                .withProperty("webpay.api-key", "clave-de-produccion-de-transbank")
                .withProperty("app.storage.driver", "local")
                .withProperty("app.upload-dir", UPLOAD_DIR)
                .withProperty("spring.mail.host", "smtp.uvostore.cl")
                .withProperty("app.swagger.enabled", "false")
                .withProperty("app.encryption-key", "dGVzdC1vbmx5LWtleS1ub3QtYS1yZWFsLW9uZS0zMmI=")
                .withProperty("spring.datasource.username", "uvostore_app")
                .withProperty("app.flyway.user", "uvostore_migrador")
                .withProperty("app.pos.enabled", "true")
                .withProperty("app.rate-limit.trusted-proxies", "10.0.0.0/8");
    }

    /** El application.properties que se despacha, sin ninguna variable de entorno: lo que arranca sin configurar. */
    static MockEnvironment shippedDefaults() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        return environment;
    }

    static UnaryOperator<String> sentry(String environment) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SentryBootstrap.ENV_DSN, SENTRY_DSN);
        env.put(SentryBootstrap.ENV_ENVIRONMENT, environment);
        return env::get;
    }

    private static Findings check(AppEnvironment environment, MockEnvironment properties,
                                  UnaryOperator<String> processEnv, DatabaseRole role) {
        return ProductionConfigurationRules.check(environment, properties, processEnv, () -> role);
    }

    private static Arguments row(String envVar, String key, String value) {
        return Arguments.of(envVar, Map.of(key, value));
    }
}
