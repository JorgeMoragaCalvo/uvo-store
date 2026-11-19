package org.uvo.uvostore.service.payment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.uvo.uvostore.config.AppEnvironment;
import org.uvo.uvostore.entity.payment.enums.PaymentGatewayType;
import org.uvo.uvostore.service.BusinessException;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * IMP-02 / IMP-07. El único sitio que decide qué pasarelas ofrece la plataforma: {@code WEBPAY_ENABLED},
 * {@code STRIPE_ENABLED} y {@code MERCADOPAGO_ENABLED}.
 *
 * <p>IMP-02 lo hizo para Webpay, cuyo contrato es de la plataforma. IMP-07 lo extendió a las otras dos: una pasarela
 * que no se homologó no se enciende, y antes cualquier tienda podía activar Stripe o MercadoPago desde el panel con
 * sus propias credenciales, homologadas o no.
 *
 * <p>Apagada, una pasarela no se puede:
 * <ul>
 *   <li>activar en el panel ({@code AdminPaymentGatewayServiceImpl.upsert}, {@code SettingsServiceImpl} para Stripe);</li>
 *   <li>ofrecer en el checkout, aunque una tienda la dejara activada antes ({@code CheckoutServiceImpl.getConfig});</li>
 *   <li>usar para crear una orden ({@code CheckoutServiceImpl.checkout});</li>
 *   <li>usar para iniciar el pago ({@code WebpayServiceImpl.createTransaction},
 *       {@code PaymentServiceImpl.createCheckoutSession}, {@code MercadoPagoServiceImpl.createPreference}).</li>
 * </ul>
 * Lo que ya empezó sí termina: el retorno, el webhook, la conciliación y el reembolso no miran el interruptor, porque
 * un pago que la pasarela ya cobró tiene que poder confirmarse o devolverse aunque la plataforma deje de ofrecerla.
 *
 * <p><b>Vacío vale {@code true} solo en development</b>, para que el desarrollo y el E2E sigan funcionando sin
 * configurar nada. En staging y production vacío impide el arranque ({@code ProductionConfigurationRules}): es una
 * decisión de alcance y tiene que estar escrita.
 */
@Component
public class PaymentPlatformPolicy {

    private final Map<PaymentGatewayType, Boolean> enabled;

    @Autowired
    public PaymentPlatformPolicy(@Value("${webpay.enabled:}") String webpay,
                                 @Value("${stripe.enabled:}") String stripe,
                                 @Value("${mercadopago.enabled:}") String mercadoPago,
                                 @Value("${app.environment:}") String environment) {
        AppEnvironment env = AppEnvironment.parse(environment);
        this.enabled = new EnumMap<>(PaymentGatewayType.class);
        enabled.put(PaymentGatewayType.WEBPAY, resolve(PaymentGatewayType.WEBPAY, webpay, env));
        enabled.put(PaymentGatewayType.STRIPE, resolve(PaymentGatewayType.STRIPE, stripe, env));
        enabled.put(PaymentGatewayType.MERCADOPAGO, resolve(PaymentGatewayType.MERCADOPAGO, mercadoPago, env));
    }

    private PaymentPlatformPolicy(Map<PaymentGatewayType, Boolean> enabled) {
        this.enabled = new EnumMap<>(enabled);
    }

    /** Para pruebas que construyen los servicios a mano: todas encendidas o todas apagadas. */
    public static PaymentPlatformPolicy of(boolean all) {
        Map<PaymentGatewayType, Boolean> map = new EnumMap<>(PaymentGatewayType.class);
        for (PaymentGatewayType gateway : PaymentGatewayType.values()) {
            map.put(gateway, all);
        }
        return new PaymentPlatformPolicy(map);
    }

    /** Para pruebas: solo las nombradas encendidas. */
    public static PaymentPlatformPolicy only(PaymentGatewayType... gateways) {
        PaymentPlatformPolicy policy = of(false);
        for (PaymentGatewayType gateway : gateways) {
            policy.enabled.put(gateway, true);
        }
        return policy;
    }

    /** La variable de entorno que decide la pasarela. */
    public static String envVar(PaymentGatewayType gateway) {
        return gateway.name() + "_ENABLED";
    }

    /** La propiedad de Spring que la lee. */
    public static String property(PaymentGatewayType gateway) {
        return gateway.name().toLowerCase(Locale.ROOT) + ".enabled";
    }

    /** El nombre que ve el comerciante. */
    public static String label(PaymentGatewayType gateway) {
        return switch (gateway) {
            case WEBPAY -> "Webpay";
            case STRIPE -> "Stripe";
            case MERCADOPAGO -> "MercadoPago";
        };
    }

    /** Para el panel y para el 400 de la API: lo que el comerciante puede leer y no puede arreglar él. */
    public static String unavailableReason(PaymentGatewayType gateway) {
        return label(gateway) + " no está disponible en esta plataforma. Elige otro medio de pago o consulta a quien "
                + "opera la tienda.";
    }

    /**
     * El valor escrito: vacío si no se puso. Solo {@code true} o {@code false}, sin las demás formas que Spring
     * acepta ({@code yes}, {@code on}, {@code 1}…): es una decisión de alcance y se escribe sin ambigüedad.
     *
     * @throws IllegalArgumentException si tiene otro valor
     */
    public static Optional<Boolean> parse(PaymentGatewayType gateway, String configured) {
        String value = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "" -> Optional.empty();
            case "true" -> Optional.of(true);
            case "false" -> Optional.of(false);
            default -> throw new IllegalArgumentException(envVar(gateway) + " es '" + configured
                    + "': tiene que ser true o false.");
        };
    }

    /**
     * Si la plataforma ofrece la pasarela. En staging y production un valor vacío nunca llega a usarse, porque
     * {@code ProductionConfigurationRules} ya impidió el arranque; aquí cuenta como apagado por si acaso.
     */
    public static boolean resolve(PaymentGatewayType gateway, String configured, AppEnvironment environment) {
        Optional<Boolean> explicit;
        try {
            explicit = parse(gateway, configured);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return explicit.orElse(!environment.isStrict());
    }

    public boolean isEnabled(PaymentGatewayType gateway) {
        return enabled.getOrDefault(gateway, false);
    }

    /** Lo mismo que {@link #isEnabled}, como un 400 con el motivo, para los puntos que tienen que rechazar. */
    public void requireEnabled(PaymentGatewayType gateway) {
        if (!isEnabled(gateway)) {
            throw new BusinessException(unavailableReason(gateway));
        }
    }
}
