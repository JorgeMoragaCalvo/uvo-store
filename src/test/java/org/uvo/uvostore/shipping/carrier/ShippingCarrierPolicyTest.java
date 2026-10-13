package org.uvo.uvostore.shipping.carrier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;
import org.uvo.uvostore.service.shipping.ShippingOption;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierPolicy;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierQuoteClient;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierQuoteRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PROD-12. Las dos condiciones que tiene que cumplir un transportista para poder ofrecerse.
 *
 * <p>Unitario y con clientes de juguete a propósito: lo que se mide es la regla, y hacerlo con los
 * clientes reales ataría estos casos a que Correos siga sin implementarse — justo lo que se espera que
 * cambie algún día. El caso de que el cliente real lo declara vive en
 * {@code ShippingCarrierQuoteClientTest}.
 */
class ShippingCarrierPolicyTest {

    private static final String PRODUCTION_URL = "https://ws.chilexpress.cl/rating/api/v1.0/rate/courier";
    private static final String SANDBOX_URL = "https://testservices.wschilexpress.com/rating/api/v1.0/rate/courier";

    private record FakeClient(ShippingCarrier carrier, boolean implemented) implements ShippingCarrierQuoteClient {

        @Override
        public boolean isImplemented() {
            return implemented;
        }

        @Override
        public Optional<ShippingOption> quote(ShippingMethod method, ShippingCarrierQuoteRequest request) {
            return Optional.empty();
        }
    }

    private static final List<ShippingCarrierQuoteClient> CLIENTS = List.of(
            new FakeClient(ShippingCarrier.CHILEXPRESS, true),
            new FakeClient(ShippingCarrier.CORREOS_CHILE, false));

    @Test
    @DisplayName("Sin lista no hay ningún transportista disponible")
    void nothingIsAvailableByDefault() {
        ShippingCarrierPolicy policy = new ShippingCarrierPolicy(CLIENTS, "", PRODUCTION_URL);

        assertThat(policy.available()).isEmpty();
        assertThat(policy.isAvailable(ShippingCarrier.CHILEXPRESS)).isFalse();
        assertThat(policy.isAvailable(null)).isFalse();
    }

    @Test
    @DisplayName("Un transportista implementado y homologado está disponible")
    void animplementedAndHomologatedCarrierIsAvailable() {
        ShippingCarrierPolicy policy = new ShippingCarrierPolicy(CLIENTS, "CHILEXPRESS", PRODUCTION_URL);

        assertThat(policy.isAvailable(ShippingCarrier.CHILEXPRESS)).isTrue();
        assertThat(policy.available()).containsExactly(ShippingCarrier.CHILEXPRESS);
    }

    @Test
    @DisplayName("Un placeholder no se habilita ni estando en la lista")
    void aPlaceholderCannotBeTurnedOn() {
        // La parte que más importa de la regla: "implementado" no es configuración. Si esto fuera una
        // lista de nombres en una propiedad, escribir CORREOS_CHILE lo encendería y el método volvería a
        // quedar activo sin aparecer nunca en el checkout.
        ShippingCarrierPolicy policy = new ShippingCarrierPolicy(
                CLIENTS, "CHILEXPRESS,CORREOS_CHILE", PRODUCTION_URL);

        assertThat(policy.isAvailable(ShippingCarrier.CORREOS_CHILE)).isFalse();
        assertThat(policy.available()).containsExactly(ShippingCarrier.CHILEXPRESS);
        assertThat(policy.unavailableReason(ShippingCarrier.CORREOS_CHILE)).contains("no está implementada");
        assertThat(policy.unavailableReason(ShippingCarrier.CHILEXPRESS)).contains("no está habilitada");
    }

    @Test
    @DisplayName("Declarar Chilexpress homologado contra el cotizador de pruebas impide el arranque")
    void theSandboxUrlIsRejectedWhenChilexpressIsHomologated() {
        assertThatThrownBy(() -> new ShippingCarrierPolicy(CLIENTS, "CHILEXPRESS", SANDBOX_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cotizador de pruebas")
                .hasMessageContaining("CHILEXPRESS_QUOTE_URL");

        // Sin homologar da igual qué URL haya: es el default de desarrollo y nadie va a cotizar con él.
        assertThat(new ShippingCarrierPolicy(CLIENTS, "", SANDBOX_URL).available()).isEmpty();
    }

    @Test
    @DisplayName("Un nombre que no existe en la lista impide el arranque, no se ignora")
    void anUnknownCarrierNameIsRejected() {
        // Ignorarlo dejaría la tienda sin cotizar envíos por una errata, y eso se descubre con una venta
        // perdida. Misma política que ReportZone con una zona horaria inválida.
        assertThatThrownBy(() -> new ShippingCarrierPolicy(CLIENTS, "chilexpres", PRODUCTION_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chilexpres");

        // Y en minúsculas o con espacios sí se acepta: es una variable de entorno escrita a mano.
        assertThat(new ShippingCarrierPolicy(CLIENTS, " chilexpress ", PRODUCTION_URL).available())
                .containsExactly(ShippingCarrier.CHILEXPRESS);
    }
}
