package org.uvo.uvostore.config;

import cl.transbank.util.WebpayApiResource;
import com.mercadopago.MercadoPagoConfig;
import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.uvo.uvostore.service.payment.RewritingHttpUtil;
import org.uvo.uvostore.service.payment.RewritingMpHttpClient;

/**
 * GAP-08. Apunta las tres pasarelas a otro servidor, para que el E2E del navegador (frontend/e2e) cobre, consulte
 * y devuelva contra dobles que deciden el resultado: rechazo, anulación, retorno perdido, webhook tardío.
 *
 * <p>Sin {@code STRIPE_API_BASE}, {@code WEBPAY_API_BASE} ni {@code MERCADOPAGO_API_BASE} no hace nada, y
 * {@link ProductionConfigurationRules} no deja arrancar fuera de desarrollo con ninguna puesta.
 *
 * <p>Los tres SDK guardan su cliente en un campo estático, así que el cambio vale para todo el proceso, igual
 * que la dirección real: no depende de la tienda. La clave de cada llamada sigue siendo la de su tienda.
 */
@Configuration
public class GatewayApiBaseConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayApiBaseConfig.class);

    private final String stripeApiBase;
    private final String webpayApiBase;
    private final String mercadoPagoApiBase;

    public GatewayApiBaseConfig(
            @Value("${stripe.api-base:}") String stripeApiBase,
            @Value("${webpay.api-base:}") String webpayApiBase,
            @Value("${mercadopago.api-base:}") String mercadoPagoApiBase) {
        this.stripeApiBase = stripeApiBase.trim();
        this.webpayApiBase = webpayApiBase.trim();
        this.mercadoPagoApiBase = mercadoPagoApiBase.trim();
    }

    @PostConstruct
    void install() {
        if (!stripeApiBase.isEmpty()) {
            Stripe.overrideApiBase(stripeApiBase);
            log.warn("STRIPE_API_BASE={}: las llamadas a Stripe van a ese servidor y no a Stripe", stripeApiBase);
        }
        if (!webpayApiBase.isEmpty()) {
            WebpayApiResource.setHttpUtil(new RewritingHttpUtil(webpayApiBase));
            log.warn("WEBPAY_API_BASE={}: las llamadas a Transbank van a ese servidor y no a Transbank", webpayApiBase);
        }
        if (!mercadoPagoApiBase.isEmpty()) {
            MercadoPagoConfig.setHttpClient(new RewritingMpHttpClient(mercadoPagoApiBase));
            log.warn("MERCADOPAGO_API_BASE={}: las llamadas a MercadoPago van a ese servidor y no a MercadoPago",
                    mercadoPagoApiBase);
        }
    }
}
