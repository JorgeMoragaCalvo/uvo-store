package org.uvo.uvostore.support;

import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PROD-12. Marca una clase de prueba que necesita la integración con UvoPOS encendida.
 *
 * <p>Se despacha apagada, así que sin esto {@code /api/sync/**} y {@code /api/webhooks/pos/**} responden
 * 404 y {@code PosOrderNotifier} no notifica nada. <b>El valor que corre en producción es el que ejercita
 * el resto de la suite</b>, y lo que PROD-12 apaga es comprobable por omisión.
 *
 * <p>Esto enciende solo el interruptor de <b>plataforma</b>. El de la tienda ({@code pos_sync_enabled})
 * lo escribe cada test con {@code setSetting(store, "pos_sync_enabled", "true")} donde haga falta: es un
 * ajuste por tienda, no configuración del contexto.
 *
 * <p>Anotación compuesta y no copias de {@code @SpringBootTest(properties = …)} por la misma razón que
 * {@link WithCustomerAccounts}: el contexto se cachea por el conjunto de propiedades declaradas, así que
 * mientras todas declaren exactamente lo mismo comparten un único contexto extra.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(properties = "app.pos.enabled=true")
public @interface WithPosIntegration {
}
