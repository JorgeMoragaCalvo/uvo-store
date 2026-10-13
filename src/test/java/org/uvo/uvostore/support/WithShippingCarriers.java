package org.uvo.uvostore.support;

import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PROD-12. Marca una clase de prueba que necesita Chilexpress habilitado como transportista.
 *
 * <p>Se despacha sin ninguno, así que sin esto configurar un método con transportista responde 400 y el
 * cotizador los omite. Correos de Chile no se puede encender por aquí ni por ningún sitio: no está
 * implementado, y eso lo declara su propio cliente.
 *
 * <p><b>Y hay que mover también la URL del cotizador</b>, que es la parte interesante: el valor por
 * defecto apunta al servicio de pruebas de Chilexpress, y declarar un transportista homologado mientras
 * cotiza contra un entorno de juguete <b>impide el arranque</b> a propósito
 * ({@code ShippingCarrierPolicy}). Así que un test que lo habilita tiene que declarar un endpoint que no
 * sea el sandbox — aquí uno inalcanzable, el mismo {@code example.invalid} que usa
 * {@code ShippingCarrierQuoteClientTest}: lo que se ejercita es la configuración del método, no una
 * cotización real, y el cliente devuelve vacío ante un error de red por contrato.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(properties = {
        "app.shipping.carriers.enabled=CHILEXPRESS",
        "chilexpress.quote-url=https://example.invalid/quote"
})
public @interface WithShippingCarriers {
}
