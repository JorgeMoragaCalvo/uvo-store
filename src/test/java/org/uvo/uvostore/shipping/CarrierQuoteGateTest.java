package org.uvo.uvostore.shipping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.enums.ShippingCarrier;
import org.uvo.uvostore.entity.shipping.enums.ShippingMethodType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.SettingRepository;
import org.uvo.uvostore.repository.ShippingMethodRepository;
import org.uvo.uvostore.repository.ShippingRateRepository;
import org.uvo.uvostore.repository.ShippingZoneRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.shipping.ShippingOption;
import org.uvo.uvostore.service.shipping.ShippingRateServiceImpl;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierPolicy;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierQuoteClient;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierQuoteDispatcher;
import org.uvo.uvostore.service.shipping.carrier.ShippingCarrierQuoteRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PROD-12. El cinturón del cotizador: una fila con transportista escrita antes del guarda no se cotiza.
 *
 * <p>La administración ya rechaza configurar o reactivar un transportista no disponible, pero las filas
 * que ya estaban en la base siguen ahí y el cotizador es el único camino por el que cobrarían un envío.
 * Es el mismo razonamiento por el que PROD-07 comprueba la bandera también en el oyente del correo.
 *
 * <p><b>Unitario, y aquí la razón importa.</b> La primera versión de este test era de integración y
 * afirmaba que no salía ninguna opción de envío — y <b>no mordía</b>: sin credenciales, el cliente de
 * Chilexpress devuelve vacío por contrato, así que el resultado era idéntico con el filtro y sin él. Lo
 * que distingue las dos situaciones no es la lista de opciones, es <b>si al transportista se le llega a
 * preguntar</b>; y eso solo se puede afirmar simulando el despachador. El tercer caso es el control: con
 * el transportista habilitado, sí se le pregunta.
 */
class CarrierQuoteGateTest {

    private final ShippingZoneRepository zoneRepository = mock(ShippingZoneRepository.class);
    private final ShippingRateRepository rateRepository = mock(ShippingRateRepository.class);
    private final ShippingMethodRepository methodRepository = mock(ShippingMethodRepository.class);
    private final SettingRepository settingRepository = mock(SettingRepository.class);
    private final ShippingCarrierQuoteDispatcher dispatcher = mock(ShippingCarrierQuoteDispatcher.class);

    private static final String PRODUCTION_URL = "https://ws.chilexpress.cl/rating";

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

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Sin transportistas homologados no se le pregunta a ninguno")
    void anUnavailableCarrierIsNeverConsulted() {
        List<ShippingOption> options = quoteWith(policy(""));

        // Esto es lo que de verdad distingue tener el filtro de no tenerlo: la llamada no se hace.
        verifyNoInteractions(dispatcher);
        assertThat(options).isEmpty();
    }

    @Test
    @DisplayName("Un placeholder tampoco, ni declarándolo en la propiedad")
    void aPlaceholderIsNeverConsulted() {
        List<ShippingOption> options = quoteWith(policy("CORREOS_CHILE"), ShippingCarrier.CORREOS_CHILE);

        verifyNoInteractions(dispatcher);
        assertThat(options).isEmpty();
    }

    @Test
    @DisplayName("Con el transportista homologado sí se le pregunta")
    void anAvailableCarrierIsConsulted() {
        // El control. Sin este caso, borrar la rama entera de transportistas pasaría los dos de arriba.
        when(dispatcher.quote(any(), any())).thenReturn(Optional.of(
                new ShippingOption(1L, "Chilexpress", BigDecimal.valueOf(4990), "2 días", false)));

        List<ShippingOption> options = quoteWith(policy("CHILEXPRESS"));

        verify(dispatcher).quote(any(), any());
        assertThat(options).hasSize(1);
        assertThat(options.getFirst().cost()).isEqualByComparingTo(BigDecimal.valueOf(4990));
    }

    private ShippingCarrierPolicy policy(String enabled) {
        return new ShippingCarrierPolicy(CLIENTS, enabled, PRODUCTION_URL);
    }

    private List<ShippingOption> quoteWith(ShippingCarrierPolicy policy) {
        return quoteWith(policy, ShippingCarrier.CHILEXPRESS);
    }

    private List<ShippingOption> quoteWith(ShippingCarrierPolicy policy, ShippingCarrier carrier) {
        Store store = new Store();
        store.setId(1L);
        store.setSlug("tienda");
        TenantContext.set(store);

        ShippingMethod method = new ShippingMethod();
        method.setId(5L);
        method.setStore(store);
        method.setName("Transportista de antes del guarda");
        method.setCode("legacy");
        method.setType(ShippingMethodType.COURIER);
        method.setHasApiIntegration(true);
        method.setCarrier(carrier);
        method.setActive(true);
        when(methodRepository.findByStoreId(1L)).thenReturn(List.of(method));
        // Sin zonas: lo que se mide es la rama de transportistas, no las tarifas propias.
        when(zoneRepository.findByStoreIdAndIsActiveTrueOrderBySortOrderAsc(1L)).thenReturn(List.of());

        ShippingRateServiceImpl service = new ShippingRateServiceImpl(
                zoneRepository, rateRepository, methodRepository, mock(ProductRepository.class),
                mock(ProductVariationRepository.class), settingRepository, dispatcher, policy);

        return service.getAvailableOptions("Metropolitana", "Santiago", BigDecimal.valueOf(10000), BigDecimal.ONE);
    }
}
