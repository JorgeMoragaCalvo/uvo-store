package org.uvo.uvostore.support;

import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PROD-07. Marca una clase de prueba que necesita las cuentas de comprador encendidas.
 *
 * <p>Se despacha apagadas, así que <b>el valor que corre en producción es el que ejercita el resto de
 * la suite</b> y lo que PROD-07 apaga es comprobable por omisión: si alguien quitara el filtro, los
 * tests de {@code CustomerAccountsDisabledTest} caerían en vez de seguir verdes por casualidad.
 *
 * <p>Una anotación compuesta y no seis copias de {@code @SpringBootTest(properties = …)} por una razón
 * técnica, no de gusto: el contexto de Spring se cachea por el conjunto de propiedades declaradas, así
 * que mientras las seis declaren <b>exactamente</b> lo mismo comparten un único contexto extra. Seis
 * literales que alguien reescriba a medias costarían un contexto por variante.
 *
 * <p>Las demás anotaciones ({@code @AutoConfigureMockMvc}, {@code @Transactional}) siguen heredándose
 * de {@link IntegrationTestSupport}; esto solo releva su {@code @SpringBootTest}, igual que hacen
 * {@code RateLimitTest} y {@code AccountThrottleTest} con los suyos.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(properties = "app.customer-accounts.enabled=true")
public @interface WithCustomerAccounts {
}
