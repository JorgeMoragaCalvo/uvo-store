package org.uvo.uvostore.service.shipping.carrier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PROD-12. El único sitio que decide si un transportista se puede ofrecer.
 *
 * <p>Un transportista está disponible solo si cumple las dos condiciones, que son de naturaleza distinta
 * a propósito:
 *
 * <ol>
 *   <li><b>Implementado</b> — un hecho del código, que cada cliente declara con
 *       {@link ShippingCarrierQuoteClient#isImplemented()}. Correos de Chile es un hueco con la forma
 *       correcta que siempre devuelve vacío, y por eso <b>ninguna propiedad lo puede habilitar</b>.</li>
 *   <li><b>Homologado</b> — una decisión de operación, que se declara en
 *       {@code app.shipping.carriers.enabled} y está <b>vacía por defecto</b>. Chilexpress sí llama a su
 *       API, pero su propio cliente admite que el contrato nunca se verificó contra la API real y que
 *       falta la tabla que traduce nombres de comuna a los códigos que Chilexpress espera. Cotizar con
 *       eso da precios equivocados, que es peor que no ofrecer el método.</li>
 * </ol>
 *
 * <p>Antes no había ninguna de las dos: el panel ofrecía los dos transportistas en un desplegable y la
 * administración aceptaba cualquiera de los dos valores del enum. El resultado era un método activo que
 * el checkout nunca mostraba, sin ningún aviso al comerciante.
 *
 * <p><b>El sandbox impide el arranque.</b> Declarar Chilexpress homologado mientras su URL sigue siendo
 * la del servicio de pruebas es la contradicción que §7.1 del plan de producción llama «sandbox
 * involuntario»: la tienda cree cobrar envíos reales y está cotizando contra un entorno de juguete. Se
 * trata como {@code ReportZone} trata una zona horaria inválida (F20) y {@code RequiredSecret} un secreto
 * de desarrollo — el contexto no levanta, en vez de dejar un aviso en un log que nadie lee.
 */
@Component
public class ShippingCarrierPolicy {

    /**
     * El host del cotizador de pruebas de Chilexpress, que es además el valor por defecto de
     * {@code chilexpress.quote-url} para que el desarrollo funcione sin credenciales.
     */
    private static final String SANDBOX_HOST_MARKER = "testservices.";

    private final Map<ShippingCarrier, Boolean> implementedByCarrier;
    private final Set<ShippingCarrier> homologated;

    public ShippingCarrierPolicy(List<ShippingCarrierQuoteClient> clients,
                                 @Value("${app.shipping.carriers.enabled:}") String enabled,
                                 @Value("${chilexpress.quote-url:}") String chilexpressQuoteUrl) {
        this.implementedByCarrier = new EnumMap<>(ShippingCarrier.class);
        clients.forEach(client -> implementedByCarrier.put(client.carrier(), client.isImplemented()));
        this.homologated = parseHomologated(enabled);
        rejectSandboxOnHomologatedChilexpress(chilexpressQuoteUrl);
    }

    /**
     * Si este transportista se puede configurar en un método de envío y cotizar en el checkout.
     */
    public boolean isAvailable(ShippingCarrier carrier) {
        return carrier != null
                && Boolean.TRUE.equals(implementedByCarrier.get(carrier))
                && homologated.contains(carrier);
    }

    /** Los disponibles, para que el panel ofrezca exactamente eso y no el enum completo. */
    public Set<ShippingCarrier> available() {
        Set<ShippingCarrier> result = EnumSet.noneOf(ShippingCarrier.class);
        for (ShippingCarrier carrier : ShippingCarrier.values()) {
            if (isAvailable(carrier)) {
                result.add(carrier);
            }
        }
        return result;
    }

    /**
     * Por qué no está disponible, en el idioma del comerciante. Los dos casos dicen cosas distintas
     * porque para él son distintas: uno no se puede arreglar configurando nada, el otro sí.
     */
    public String unavailableReason(ShippingCarrier carrier) {
        if (carrier == null) {
            return "No se indicó transportista.";
        }
        if (!Boolean.TRUE.equals(implementedByCarrier.get(carrier))) {
            return "La integración con " + carrier.name() + " no está implementada, así que un método "
                    + "configurado con ella nunca podría cotizar. Usa tarifas propias por zona.";
        }
        return "La integración con " + carrier.name() + " no está habilitada en esta instalación: "
                + "requiere homologación del contrato y del mapeo de comunas antes de cobrar envíos "
                + "con ella. Se habilita en app.shipping.carriers.enabled.";
    }

    private static Set<ShippingCarrier> parseHomologated(String enabled) {
        Set<ShippingCarrier> result = EnumSet.noneOf(ShippingCarrier.class);
        if (enabled == null || enabled.isBlank()) {
            return result;
        }
        // Un nombre que no existe en el enum impide el arranque en vez de ignorarse: si alguien escribe
        // "chilexpres" en la variable de producción, el fallo es que no se ofrece ningún envío, y eso se
        // descubre con una venta perdida, no con un log.
        Arrays.stream(enabled.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .forEach(name -> {
                    try {
                        result.add(ShippingCarrier.valueOf(name.toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException("app.shipping.carriers.enabled nombra un "
                                + "transportista que no existe: '" + name + "'. Valores válidos: "
                                + Arrays.toString(ShippingCarrier.values()), e);
                    }
                });
        return result;
    }

    private void rejectSandboxOnHomologatedChilexpress(String quoteUrl) {
        if (!homologated.contains(ShippingCarrier.CHILEXPRESS)) {
            return;
        }
        if (quoteUrl != null && quoteUrl.contains(SANDBOX_HOST_MARKER)) {
            throw new IllegalStateException("Chilexpress está declarado habilitado en "
                    + "app.shipping.carriers.enabled pero chilexpress.quote-url sigue apuntando al "
                    + "cotizador de pruebas ('" + quoteUrl + "'). Cobrar envíos con precios de un "
                    + "entorno de juguete es peor que no ofrecerlos: configura CHILEXPRESS_QUOTE_URL "
                    + "con el endpoint de producción, o quita CHILEXPRESS de la lista.");
        }
    }
}
