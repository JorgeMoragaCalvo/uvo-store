package org.uvo.uvostore.service.settings;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.uvo.uvostore.service.BusinessException;

import java.math.BigDecimal;
import java.util.Set;

/**
 * F17. El único sitio que decide qué es un ajuste de dinero válido, al escribirlo y al leerlo.
 *
 * <p>Los ajustes viven en una tabla clave/valor de texto libre y se volvían a parsear en cada
 * consumidor. Nadie comprobaba nada al guardarlos, así que un {@code tax_rate = abc} tecleado en el
 * panel —el campo del formulario es un {@code input} de texto, sin tipo— pasaba a ser un fallo del
 * storefront: cotizar y comprar respondían 400 con el mensaje interno de {@code BigDecimal}. Y como
 * es un 400 y no un 500, <b>no llega a Sentry</b>: la tienda se quedaba sin vender sin que nadie
 * recibiera una alerta.
 *
 * <p>Peor que el texto no numérico eran los números válidos: {@code tax_rate = -19} cobra por debajo
 * del precio del producto sin lanzar nada, y {@code -100} con precios con IVA incluido divide por cero.
 *
 * <p>Y había <b>dos parsers que no aceptan el mismo texto</b>: {@code Double.parseDouble} recorta
 * espacios y admite sufijo {@code d}/{@code f}, {@code new BigDecimal(String)} no admite ninguno de los
 * dos. Con {@code tax_rate = " 19 "} el checkout cobraba bien mientras {@code /cart/calculate} y
 * {@code /checkout/config} respondían 400 — el mismo ajuste válido e inválido a la vez según por dónde
 * se entrara. Por eso {@link #decimal} es ahora el único lector, y por eso
 * {@link #normalizedDecimal} recorta antes de guardar.
 */
public final class SettingValues {

    private static final Logger log = LoggerFactory.getLogger(SettingValues.class);

    /**
     * Catálogo de divisas admitidas. Tiene una entrada porque el sistema entero es CLP y no por
     * omisión: {@link org.uvo.uvostore.service.Money} redondea a la unidad, Webpay y MercadoPago
     * llevan {@code "CLP"} fijo, el formato de la SPA es {@code es-CL} sin decimales y ninguna entidad
     * guarda en qué divisa se cobró una orden.
     *
     * <p>Admitir otra sin eso no es una funcionalidad, es un cobro mal hecho: {@code unit_amount} de
     * Stripe va en la unidad mínima de la divisa, así que un total de 11888 enviado como {@code usd}
     * cobra 118,88 dólares por un pedido de unos 12 — y
     * {@code PaymentServiceImpl.stripeAmount} compara 11888 contra 11888 y lo da por bueno, porque la
     * comprobación de importe es ciega a la unidad. El día que se admita otra divisa, esta constante y
     * la escala de {@code Money} se mueven juntas.
     */
    public static final Set<String> SUPPORTED_CURRENCIES = Set.of("CLP");

    private SettingValues() {
    }

    /**
     * El valor almacenado como número, o el defecto si el ajuste no existe.
     *
     * <p>Un valor guardado que no se puede leer <b>falla avisando</b> en vez de caer al defecto: caer a
     * 19 % sería inventarle un impuesto a una tienda que quizá está exenta, o sea cobrar en silencio
     * algo distinto de lo configurado. Se registra en Sentry porque es una configuración rota, no un
     * error del cliente que la sufre.
     */
    public static BigDecimal decimal(String key, String rawValue, BigDecimal fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(rawValue.trim());
        } catch (NumberFormatException e) {
            log.error("Ajuste '{}' no es un número válido: '{}'", key, rawValue);
            Sentry.captureMessage("Ajuste de tienda inválido: " + key + "='" + rawValue + "'");
            throw new BusinessException(
                    "La configuración de la tienda tiene un valor inválido en '" + key
                            + "'. Avisa al administrador para corregirlo.");
        }
    }

    /** {@code tax_rate}: porcentaje, 0 a 100. Devuelve el texto ya recortado, listo para guardar. */
    public static String normalizedTaxRate(String rawValue) {
        BigDecimal rate = normalizedDecimal(rawValue, "la tasa de impuesto");
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
            // El negativo cobra menos que el precio del producto; -100 divide por cero cuando los
            // precios incluyen IVA; y 1900 (el dedo que resbaló en el %) multiplica el total por 20.
            throw new BusinessException("La tasa de impuesto debe estar entre 0 y 100.");
        }
        return rawValue.trim();
    }

    /**
     * Importes en pesos: no negativos y <b>sin parte fraccionaria</b>. El CLP no tiene centavos, que es
     * la regla que {@link org.uvo.uvostore.service.Money} fija para todo el dinero del sistema; un
     * umbral de 1500,50 no se puede comparar con un total que siempre es entero.
     */
    public static String normalizedAmount(String rawValue, String label) {
        BigDecimal amount = normalizedDecimal(rawValue, label);
        if (amount.signum() < 0) {
            throw new BusinessException(capitalized(label) + " no puede ser negativo.");
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new BusinessException(capitalized(label) + " debe ser un monto en pesos enteros, sin decimales.");
        }
        return rawValue.trim();
    }

    /** {@code currency}: del catálogo, en mayúsculas. */
    public static String normalizedCurrency(String rawValue) {
        String currency = rawValue == null ? "" : rawValue.trim().toUpperCase();
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new BusinessException(
                    "Divisa no admitida: '" + rawValue + "'. Las divisas admitidas son: "
                            + String.join(", ", SUPPORTED_CURRENCIES) + ".");
        }
        return currency;
    }

    private static BigDecimal normalizedDecimal(String rawValue, String label) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new BusinessException("Falta " + label + ".");
        }
        try {
            return new BigDecimal(rawValue.trim());
        } catch (NumberFormatException e) {
            // Sin Sentry, al contrario que decimal(): aquí el valor malo viene de quien está rellenando
            // el formulario ahora mismo y se le responde a él. No hay nada roto en la tienda.
            throw new BusinessException(capitalized(label) + " debe ser un número. Recibido: '" + rawValue + "'.");
        }
    }

    private static String capitalized(String label) {
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }
}
