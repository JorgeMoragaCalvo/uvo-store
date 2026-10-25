package org.uvo.uvostore.config;

import cl.transbank.common.IntegrationApiKeys;
import cl.transbank.common.IntegrationCommerceCodes;
import org.springframework.core.env.PropertyResolver;
import org.uvo.uvostore.security.IpRanges;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * PROD-08a. Qué configuración no sirve para un despliegue de verdad, y por qué.
 *
 * <p>Cada secreto ya se validaba por su cuenta ({@code RequiredSecret}, {@code EncryptionKeyHolder},
 * {@code ShippingCarrierPolicy}), pero los valores por defecto de {@code application.properties} son los de
 * DESARROLLO y nada impedía arrancar con ellos en producción: enlaces {@code http://...:5173} en los correos,
 * Webpay cobrando en el sandbox, imágenes en una carpeta que se pierde con el contenedor, correo que se omite
 * en silencio, Swagger publicando el mapa de la API. Aquí se decide, en un solo sitio, qué de eso es un
 * <b>error</b> (el contexto no levanta) y qué es una <b>advertencia</b> (una línea en el log).
 *
 * <p>Las reglas solo se aplican fuera de {@link AppEnvironment#DEVELOPMENT}; {@code staging} tiene las mismas
 * que {@code production} salvo la de Webpay, porque probar contra el sandbox es justo para lo que existe.
 *
 * <p>Es una función pura a propósito, como {@code SentryBootstrap}: recibe las propiedades, el entorno del
 * proceso (los {@code SENTRY_*} se leen con {@code System.getenv} y Spring no los ve) y la base por un
 * {@link Supplier}, que en desarrollo no se llega a llamar. Quien la aplica al arrancar es
 * {@link ProductionConfigurationCheck}.
 */
public final class ProductionConfigurationRules {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private ProductionConfigurationRules() {
    }

    /** Lo que se encontró. Los errores impiden el arranque; las advertencias solo se registran. */
    public record Findings(List<String> errors, List<String> warnings) {

        public Findings {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
    }

    public static Findings check(AppEnvironment environment, PropertyResolver properties,
                                 UnaryOperator<String> processEnv, Supplier<DatabaseRole> database) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!environment.isStrict()) {
            // La red para el olvido de la señal: un dominio de plataforma de verdad es lo más parecido a un
            // despliegue que se puede ver desde aquí sin adivinar nada más.
            String domain = text(properties, "app.public-url.platform-domain", "localhost");
            if (!isLocalDomain(domain)) {
                warnings.add("PUBLIC_PLATFORM_DOMAIN es '" + domain + "', que parece un despliegue, pero "
                        + "APP_ENVIRONMENT no está definido: las comprobaciones de producción NO se aplican. "
                        + "Define APP_ENVIRONMENT=production (o staging).");
            }
            return new Findings(errors, warnings);
        }

        publicUrls(properties, errors);
        if (environment == AppEnvironment.PRODUCTION) {
            webpay(properties, errors);
        }
        storage(properties, errors);
        mail(properties, errors);
        swagger(properties, errors);
        platformNetworks(properties, errors);
        metricsToken(properties, errors, warnings);
        encryptionKey(properties, errors);
        sentry(environment, processEnv, errors, warnings);
        databaseRole(properties, database, errors);

        if (!bool(properties, "app.pos.enabled", false)) {
            warnings.add("POS_ENABLED=false: la integración con UvoPOS está apagada, así que no se notifica ninguna "
                    + "venta ni se acepta el catálogo del POS. Es lo esperado hasta que POS-1 confirme el contrato.");
        }
        if (text(properties, "app.rate-limit.trusted-proxies", "").isBlank()) {
            warnings.add("RATE_LIMIT_TRUSTED_PROXIES está vacío: si hay un proxy inverso delante, todos los clientes "
                    + "comparten un contador y el login se corta a los 5 intentos del conjunto. Pon la IP o el CIDR "
                    + "del proxy.");
        }
        return new Findings(errors, warnings);
    }

    private static void publicUrls(PropertyResolver properties, List<String> errors) {
        requireHttps(text(properties, "app.public-url.storefront", ""), "PUBLIC_STOREFRONT_URL", errors);
        requireHttps(text(properties, "app.public-url.api", ""), "PUBLIC_API_URL", errors);

        String domain = text(properties, "app.public-url.platform-domain", "localhost");
        if (isLocalDomain(domain)) {
            errors.add("PUBLIC_PLATFORM_DOMAIN es '" + domain + "': cada tienda vive en <slug>.<dominio>, y con un "
                    + "dominio local, una IP o un nombre sin punto ningún enlace de correo ni retorno de pasarela "
                    + "llega a la tienda. Pon el dominio público de la plataforma, por ejemplo uvostore.cl.");
        }
    }

    private static void requireHttps(String template, String envVar, List<String> errors) {
        if (!template.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
            errors.add(envVar + " es '" + template + "' y no usa https: esa dirección va en correos y en los retornos "
                    + "de las pasarelas de pago. En producción es https://{host}.");
        }
    }

    /**
     * Las credenciales del sandbox se comparan con las del propio SDK y no con literales copiados: son las que
     * {@code application.properties} trae por defecto para que el desarrollo funcione sin configurar nada.
     *
     * <p>Y el ambiente se exige <b>exacto</b> porque {@code WebpayServiceImpl} trata cualquier valor distinto de
     * {@code production} como integración: con una errata ({@code produccion}) la tienda cobraba en el sandbox,
     * marcaba las órdenes como pagadas y no entraba ni un peso.
     */
    private static void webpay(PropertyResolver properties, List<String> errors) {
        String environment = text(properties, "webpay.environment", "integration").trim();
        if (!"production".equalsIgnoreCase(environment)) {
            errors.add("WEBPAY_ENVIRONMENT es '" + environment + "': Webpay cobra en el sandbox de Transbank, y una "
                    + "orden pagada ahí queda como pagada sin que entre dinero. En producción es "
                    + "WEBPAY_ENVIRONMENT=production.");
        }
        if (IntegrationCommerceCodes.WEBPAY_PLUS_MALL.equals(text(properties, "webpay.parent-commerce-code", "").trim())) {
            errors.add("WEBPAY_PARENT_COMMERCE_CODE es el código público del sandbox de Transbank. Pon el código de "
                    + "comercio padre que Transbank emitió para la plataforma.");
        }
        if (IntegrationApiKeys.WEBPAY.equals(text(properties, "webpay.api-key", "").trim())) {
            errors.add("WEBPAY_API_KEY es la clave pública del sandbox de Transbank. Pon la clave de producción que "
                    + "Transbank emitió para la plataforma.");
        }
    }

    private static void storage(PropertyResolver properties, List<String> errors) {
        String driver = text(properties, "app.storage.driver", "local").trim().toLowerCase(Locale.ROOT);
        if (driver.equals("local")) {
            String uploadDir = text(properties, "app.upload-dir", "uploads");
            if (!isAbsolute(uploadDir)) {
                errors.add("APP_UPLOAD_DIR es '" + uploadDir + "', una ruta relativa: queda dentro del directorio de "
                        + "trabajo del proceso, y en un contenedor las imágenes subidas se pierden con cada despliegue. "
                        + "Pon la ruta absoluta de un volumen persistente, o usa APP_STORAGE_DRIVER=s3.");
            }
        } else if (driver.equals("s3") && text(properties, "app.storage.s3.bucket", "").isBlank()) {
            errors.add("APP_STORAGE_S3_BUCKET está vacío con APP_STORAGE_DRIVER=s3: la primera imagen que se suba "
                    + "fallará. Pon el bucket donde se guardan las imágenes.");
        }
    }

    private static void mail(PropertyResolver properties, List<String> errors) {
        if (text(properties, "spring.mail.host", "").isBlank()) {
            errors.add("MAIL_HOST está vacío: el correo se omite en silencio, así que no llegan las invitaciones de "
                    + "administrador, los restablecimientos de contraseña ni las confirmaciones de compra. Configura "
                    + "el relay SMTP (MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD, MAIL_SMTP_AUTH=true).");
        }
    }

    private static void swagger(PropertyResolver properties, List<String> errors) {
        if (bool(properties, "app.swagger.enabled", true)) {
            errors.add("SWAGGER_ENABLED no es false: /swagger-ui.html y /v3/api-docs son públicos y publican el mapa "
                    + "completo de la API, incluida la de plataforma. Pon SWAGGER_ENABLED=false.");
        }
    }

    /**
     * POS-6. La API de plataforma crea tiendas: solo se llama desde la red privada. Un {@code /0} en la lista la abre
     * a todo Internet, que es justo lo que la lista existe para impedir. Las entradas mal escritas no se miran aquí:
     * {@code PlatformNetworkPolicy} ya impide el arranque por ellas.
     */
    private static void platformNetworks(PropertyResolver properties, List<String> errors) {
        String networks = text(properties, "app.platform.allowed-networks", "");
        if (IpRanges.parseLenient(networks, entry -> { }).coversEverything()) {
            errors.add("PLATFORM_ALLOWED_NETWORKS es '" + networks + "' e incluye un rango /0: la API de plataforma, "
                    + "que crea tiendas, queda abierta a todo Internet. Pon solo la red privada desde la que llama "
                    + "UvoPOS, por ejemplo 10.0.0.0/8.");
        }
    }

    /**
     * PROD-15b. Sin token, {@code /actuator/prometheus} no existe ({@code MetricsAuthFilter}): no hay riesgo, pero
     * tampoco métricas, y las alertas de PROD-15c dependen de ellas. Con un token corto, la única defensa que queda
     * dentro de la red privada se adivina.
     */
    static final int METRICS_TOKEN_MIN_LENGTH = 32;

    private static void metricsToken(PropertyResolver properties, List<String> errors, List<String> warnings) {
        String token = text(properties, "app.metrics.token", "").trim();
        if (token.isEmpty()) {
            warnings.add("METRICS_TOKEN está vacío: /actuator/prometheus no existe y no hay métricas de pendientes ni "
                    + "de divergencias con el POS para alertar. Genera uno con: openssl rand -hex 32");
        } else if (token.length() < METRICS_TOKEN_MIN_LENGTH) {
            errors.add("METRICS_TOKEN tiene " + token.length() + " caracteres; hacen falta al menos "
                    + METRICS_TOKEN_MIN_LENGTH + ". Genera uno con: openssl rand -hex 32");
        }
    }

    private static void encryptionKey(PropertyResolver properties, List<String> errors) {
        // EncryptionKeyHolder solo avisa, porque en local cambiar la clave vuelve ilegibles las credenciales ya
        // cifradas. Una instalación de producción empieza vacía y no tiene esa excusa.
        if (RequiredSecret.LEGACY_ENCRYPTION_KEY.equals(text(properties, "app.encryption-key", ""))) {
            errors.add("APP_ENCRYPTION_KEY es el valor que estuvo commiteado en application.properties: está en el "
                    + "historial de git y no protege nada. Genera una nueva con: " + RequiredSecret.GENERATE_HINT);
        }
    }

    /**
     * Sin {@code SENTRY_ENVIRONMENT}, el SDK informa como {@code production}: un staging que la olvide mezcla sus
     * errores con los de producción. Se exige que coincida con {@code APP_ENVIRONMENT} solo si hay DSN, porque sin
     * DSN no se envía nada.
     */
    private static void sentry(AppEnvironment environment, UnaryOperator<String> processEnv,
                               List<String> errors, List<String> warnings) {
        String dsn = processEnv.apply(SentryBootstrap.ENV_DSN);
        if (dsn == null || dsn.isBlank()) {
            warnings.add("SENTRY_DSN está vacío: los errores del servidor solo quedan en el log local.");
            return;
        }
        String reported = processEnv.apply(SentryBootstrap.ENV_ENVIRONMENT);
        reported = reported == null || reported.isBlank() ? null : reported.trim();
        if (reported == null || !reported.equalsIgnoreCase(environment.label())) {
            errors.add("SENTRY_ENVIRONMENT es " + (reported == null ? "vacío (el SDK informa 'production')"
                    : "'" + reported + "'") + " y APP_ENVIRONMENT es '" + environment.label() + "': los errores "
                    + "se archivarían en otro ambiente. Pon SENTRY_ENVIRONMENT=" + environment.label()
                    + " como variable de entorno real del proceso (no en el .env).");
        }
    }

    /**
     * §7.2: el rol de la aplicación no tiene DDL; las migraciones las aplica otro, el de {@code FLYWAY_USER}.
     * Las dos mitades hacen falta: sin rol de Flyway, el de la aplicación tiene que ser el dueño de las tablas para
     * migrarlas.
     */
    private static void databaseRole(PropertyResolver properties, Supplier<DatabaseRole> database,
                                     List<String> errors) {
        String flywayUser = text(properties, "app.flyway.user", "").trim();
        String appUser = text(properties, "spring.datasource.username", "").trim();
        if (flywayUser.isEmpty()) {
            errors.add("FLYWAY_USER está vacío: las migraciones corren con el rol de la aplicación, que entonces "
                    + "necesita permisos de DDL. Crea un rol propio para Flyway y ponlo en FLYWAY_USER y "
                    + "FLYWAY_PASSWORD (ver CLAUDE.md, PROD-08a).");
        } else if (flywayUser.equals(appUser)) {
            errors.add("FLYWAY_USER es el mismo rol que DB_USERNAME ('" + appUser + "'): separarlos es lo que deja a "
                    + "la aplicación sin permisos de DDL. Usa un rol distinto para cada uno.");
        }

        DatabaseRole role = database.get();
        if (role.superuser()) {
            errors.add("DB_USERNAME ('" + role.name() + "') es superusuario de PostgreSQL. La aplicación tiene que "
                    + "conectarse con un rol que solo lea y escriba datos (ver CLAUDE.md, PROD-08a).");
            return;
        }
        if (role.canCreateInSchema()) {
            errors.add("DB_USERNAME ('" + role.name() + "') puede crear objetos en el esquema. Quítale el permiso: "
                    + "REVOKE CREATE ON SCHEMA public FROM " + role.name() + ".");
        }
        if (role.ownsTables()) {
            errors.add("DB_USERNAME ('" + role.name() + "') es dueño de tablas del esquema, y el dueño puede alterarlas "
                    + "o borrarlas. Pásalas al rol de Flyway: REASSIGN OWNED BY " + role.name() + " TO <rol de Flyway>.");
        }
    }

    static boolean isLocalDomain(String domain) {
        String host = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        return host.isEmpty()
                || host.equals("localhost")
                || host.endsWith(".localhost")
                || !host.contains(".")
                || IPV4.matcher(host).matches();
    }

    private static boolean isAbsolute(String path) {
        try {
            return Path.of(path.trim()).isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static String text(PropertyResolver properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : value;
    }

    /** Con la misma conversión que usa Spring para inyectarlo: {@code yes} y {@code on} también son verdadero. */
    private static boolean bool(PropertyResolver properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.TRUE.equals(properties.getProperty(key, Boolean.class));
    }
}
