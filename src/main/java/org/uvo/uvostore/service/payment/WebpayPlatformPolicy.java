package org.uvo.uvostore.service.payment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.config.AppEnvironment;
import org.uvo.uvostore.service.BusinessException;

import java.util.Locale;
import java.util.Optional;

/**
 * IMP-02. El único sitio que decide si la plataforma ofrece Webpay ({@code WEBPAY_ENABLED}).
 *
 * <p>Webpay Plus Mall es de la plataforma: el código de comercio padre y la API key los emite Transbank una vez, a
 * quien opera la instalación, y cada tienda solo pone su código hijo. Si la plataforma no tiene ese contrato, lo
 * honesto es no ofrecerlo, y antes no había cómo decirlo: {@code ProductionConfigurationRules} exigía las
 * credenciales de producción siempre, así que salir sin Webpay obligaba a rellenarlas con valores ficticios, y
 * una tienda podía activarlo en el panel igualmente.
 *
 * <p>Por eso el interruptor gobierna las dos cosas a la vez. Apagado, no se exigen credenciales al arrancar, y
 * tampoco se puede:
 * <ul>
 *   <li>activar Webpay en el panel ({@code AdminPaymentGatewayServiceImpl.upsert});</li>
 *   <li>ofrecerlo en el checkout, aunque una tienda lo dejara activado antes ({@code CheckoutServiceImpl.getConfig});</li>
 *   <li>crear una orden con {@code paymentMethod=webpay} ({@code CheckoutServiceImpl.checkout});</li>
 *   <li>iniciar el pago ({@code WebpayServiceImpl.createTransaction}).</li>
 * </ul>
 * Lo que ya empezó sí termina: el retorno, la conciliación y el reembolso no miran el interruptor, porque un pago
 * que Transbank ya cobró tiene que poder confirmarse o devolverse aunque la plataforma deje de ofrecer Webpay.
 *
 * <p><b>Vacío vale {@code true} solo en development</b>, para que el desarrollo y el E2E sigan funcionando sin
 * configurar nada. En staging y production vacío impide el arranque ({@code ProductionConfigurationRules}): es una
 * decisión de alcance y tiene que estar escrita. Mismo criterio que {@code CustomerAccountsPolicy} y
 * {@code ShippingCarrierPolicy}: de plataforma, no por tienda.
 */
@Component
public class WebpayPlatformPolicy {

    /** Para el panel y para el 400 de la API: lo que el comerciante puede leer y no puede arreglar él. */
    public static final String UNAVAILABLE_REASON =
            "Webpay no está disponible en esta plataforma. Elige otro medio de pago o consulta a quien opera la tienda.";

    private final boolean enabled;

    @Autowired
    public WebpayPlatformPolicy(@Value("${webpay.enabled:}") String configured,
                                @Value("${app.environment:}") String environment) {
        this(resolve(configured, AppEnvironment.parse(environment)));
    }

    private WebpayPlatformPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    /** Para pruebas que construyen los servicios a mano. */
    public static WebpayPlatformPolicy of(boolean enabled) {
        return new WebpayPlatformPolicy(enabled);
    }

    /**
     * El valor escrito: vacío si no se puso. Solo {@code true} o {@code false}, sin las demás formas que Spring
     * acepta ({@code yes}, {@code on}, {@code 1}…): es una decisión de alcance y se escribe sin ambigüedad.
     *
     * @throws IllegalArgumentException si tiene otro valor
     */
    public static Optional<Boolean> parse(String configured) {
        String value = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "" -> Optional.empty();
            case "true" -> Optional.of(true);
            case "false" -> Optional.of(false);
            default -> throw new IllegalArgumentException("WEBPAY_ENABLED es '" + configured
                    + "': tiene que ser true o false.");
        };
    }

    /**
     * Si la plataforma ofrece Webpay. En staging y production un valor vacío nunca llega a usarse, porque
     * {@code ProductionConfigurationRules} ya impidió el arranque; aquí cuenta como apagado por si acaso.
     */
    public static boolean resolve(String configured, AppEnvironment environment) {
        Optional<Boolean> explicit;
        try {
            explicit = parse(configured);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return explicit.orElse(!environment.isStrict());
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Lo mismo que {@link #isEnabled()}, como un 400 con el motivo, para los puntos que tienen que rechazar. */
    public void requireEnabled() {
        if (!enabled) {
            throw new BusinessException(UNAVAILABLE_REASON);
        }
    }
}
