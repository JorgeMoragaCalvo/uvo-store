package org.uvo.uvostore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.function.UnaryOperator;

/**
 * PROD-08a. Aplica {@link ProductionConfigurationRules} al arrancar: con errores, el contexto no levanta.
 *
 * <p>Va en el constructor, como el resto de validaciones de arranque del proyecto ({@code RequiredSecret},
 * {@code ShippingCarrierPolicy}, {@code ReportZone}): un aviso en un log que nadie lee no impide nada. Y
 * <b>todos los errores salen juntos</b>, en un solo mensaje, para que quien despliega los arregle de una vez y
 * no de uno en uno, reinicio a reinicio.
 *
 * <p>En {@code development} no se consulta la base ni se comprueba nada, así que la suite de tests y el
 * desarrollo local arrancan igual que antes.
 */
@Component
public class ProductionConfigurationCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigurationCheck.class);

    @Autowired
    public ProductionConfigurationCheck(Environment springEnvironment, ObjectProvider<JdbcTemplate> jdbc) {
        this(springEnvironment, System::getenv, jdbc);
    }

    ProductionConfigurationCheck(Environment springEnvironment, UnaryOperator<String> processEnv,
                                 ObjectProvider<JdbcTemplate> jdbc) {
        AppEnvironment environment = AppEnvironment.parse(springEnvironment.getProperty("app.environment"));

        ProductionConfigurationRules.Findings findings = ProductionConfigurationRules.check(environment,
                springEnvironment, processEnv, () -> DatabaseRole.query(jdbc.getObject()));

        findings.warnings().forEach(warning -> log.warn("Configuración: {}", warning));
        if (!findings.errors().isEmpty()) {
            throw new IllegalStateException("La configuración no sirve para APP_ENVIRONMENT=" + environment.label()
                    + " (" + findings.errors().size() + " problemas):\n  - "
                    + String.join("\n  - ", findings.errors()));
        }
        if (environment.isStrict()) {
            log.info("APP_ENVIRONMENT={}: configuración de producción verificada, {} advertencias.",
                    environment.label(), findings.warnings().size());
        } else {
            log.info("APP_ENVIRONMENT=development: las comprobaciones de producción no se aplican.");
        }
    }
}
