package org.uvo.uvostore.url;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.controller.order.CreateCheckoutSessionRequest;
import org.uvo.uvostore.controller.order.MercadoPagoCreateRequest;
import org.uvo.uvostore.controller.order.WebpayCreateRequest;
import org.uvo.uvostore.support.IntegrationTestSupport;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-04. Un build antiguo de la SPA que todavía mande las URLs de retorno sigue funcionando, y esas URLs
 * no influyen en nada.
 *
 * <p>Los tres {@code *Request} perdieron esos campos al pasar la construcción al servidor. Esto fija la
 * compatibilidad que se afirma en su javadoc: la petición no se rechaza, el campo se descarta. Importa
 * porque el frontend y el backend se despliegan por separado, así que durante un despliegue hay clientes
 * viejos hablando con la API nueva — y lo que no debe pasar es que el checkout responda 400 a mitad de un
 * pago por un campo que ya no se usa.
 *
 * <p>Se usa el {@code ObjectMapper} del contexto, que en Boot 4 es <b>Jackson 3</b>
 * ({@code tools.jackson.databind}), para que lo que se comprueba sea el mismo que deserializa las
 * peticiones de verdad y no otra instancia con otra configuración.
 */
class LegacyReturnUrlFieldsTest extends IntegrationTestSupport {

    @Autowired
    private ObjectMapper httpObjectMapper;

    @Test
    @DisplayName("Stripe: el cuerpo antiguo se acepta y successUrl/cancelUrl se descartan")
    void theLegacyStripeBodyIsStillAccepted() {
        String legacy = """
                {"orderId":7,"successUrl":"https://evil.example/cobrado","cancelUrl":"https://evil.example"}""";

        CreateCheckoutSessionRequest request = httpObjectMapper.readValue(legacy, CreateCheckoutSessionRequest.class);

        assertThat(request.orderId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("MercadoPago: ídem con las tres URLs de retorno")
    void theLegacyMercadoPagoBodyIsStillAccepted() {
        String legacy = """
                {"orderId":7,"successUrl":"https://evil.example","failureUrl":"https://evil.example","pendingUrl":"https://evil.example"}""";

        assertThat(httpObjectMapper.readValue(legacy, MercadoPagoCreateRequest.class).orderId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Webpay: ídem con returnUrl")
    void theLegacyWebpayBodyIsStillAccepted() {
        String legacy = """
                {"orderId":7,"returnUrl":"https://evil.example/return"}""";

        assertThat(httpObjectMapper.readValue(legacy, WebpayCreateRequest.class).orderId()).isEqualTo(7L);
    }
}
