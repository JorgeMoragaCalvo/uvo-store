package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.ShippingRate;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.entity.shipping.enums.RateType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.ShippingMethodRepository;
import org.uvo.uvostore.repository.ShippingRateRepository;
import org.uvo.uvostore.repository.ShippingZoneRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F16. La orden deja escrito con qué se cotizó el envío, y un método desactivado deja de cotizarse.
 *
 * <p>Las cuatro columnas (`shipping_method`, `shipping_zone_id`, `shipping_method_id`,
 * `shipping_rate_id`) existían en la tabla y nadie las rellenaba. Además de perderse la trazabilidad, eso
 * dejaba <b>inertes dos comprobaciones</b>: el panel se niega a borrar una zona o un método "con órdenes
 * asociadas", y como ninguna orden apuntaba a ninguno, siempre contaban cero — se podía borrar la zona que
 * explicaba el precio de una venta.
 */
class ShippingSelectionTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ShippingZoneRepository zoneRepository;
    @Autowired
    private ShippingMethodRepository methodRepository;
    @Autowired
    private ShippingRateRepository rateRepository;

    @Test
    @DisplayName("La orden guarda el método, la zona y la tarifa que respaldaron el precio")
    void theOrderRecordsWhatBackedThePrice() throws Exception {
        Store store = createStore("ship-record");
        Zone zone = seedZone(store, "Despacho a domicilio", BigDecimal.valueOf(3990), true);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));

        Order order = checkoutAndLoad(store, product);

        assertThat(order.getShippingCost()).isEqualByComparingTo("3990");
        assertThat(order.getShippingMethodRef()).isNotNull();
        assertThat(order.getShippingMethodRef().getId()).isEqualTo(zone.method.getId());
        assertThat(order.getShippingRate().getId()).isEqualTo(zone.rate.getId());
        assertThat(order.getShippingZone().getId()).isEqualTo(zone.zone.getId());
        // El nombre en texto es el único dato que sobrevive si el método se borra más adelante.
        assertThat(order.getShippingMethod()).isEqualTo("Despacho a domicilio");
    }

    @Test
    @DisplayName("Y con eso reviven los guardas de borrado del panel")
    void theDeleteGuardsStartWorking() throws Exception {
        Store store = createStore("ship-guard");
        Zone zone = seedZone(store, "Despacho", BigDecimal.valueOf(3990), true);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        checkoutAndLoad(store, product);

        User admin = createAdmin(store, "ship-guard");
        String token = loginAdmin(store, admin);

        // Los dos endpoints dicen desde siempre "no se puede eliminar … con órdenes asociadas"; hasta
        // ahora la cuenta salía cero y borraban sin más.
        mockMvc.perform(delete("/api/admin/shipping/methods/" + zone.method.getId())
                        .header("Host", hostHeader(store)).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/admin/shipping/zones/" + zone.zone.getId())
                        .header("Host", hostHeader(store)).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Un método desactivado deja de cotizarse")
    void aDeactivatedMethodIsNoLongerQuoted() throws Exception {
        // findByZoneIdAndIsActiveTrue filtra el flag de la TARIFA, no el del MÉTODO, así que desactivar
        // el método no lo quitaba de las cotizaciones: se seguía cobrando su tarifa. Si era el único de la
        // zona, ahora no hay envío posible y el checkout lo dice (409) en vez de cobrar.
        Store store = createStore("ship-inactive");
        seedZone(store, "Despacho", BigDecimal.valueOf(3990), false);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));

        mockMvc.perform(checkout(store, product)).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Con dos métodos activos se cobra el más barato, y la orden guarda ESE")
    void theCheapestIsChargedAndRecorded() throws Exception {
        Store store = createStore("ship-cheapest");
        Zone cheap = seedZone(store, "Económico", BigDecimal.valueOf(2990), true);
        // Segundo método más caro, en la misma zona.
        ShippingMethod express = new ShippingMethod();
        express.setStore(store);
        express.setName("Exprés");
        express.setCode("expres-" + nextSeq());
        express.setActive(true);
        express = methodRepository.save(express);
        ShippingRate expressRate = new ShippingRate();
        expressRate.setStore(store);
        expressRate.setZone(cheap.zone);
        expressRate.setMethod(express);
        expressRate.setName("Tarifa exprés");
        expressRate.setRateType(RateType.FLAT);
        expressRate.setFlatRate(BigDecimal.valueOf(7990));
        expressRate.setActive(true);
        rateRepository.save(expressRate);

        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        Order order = checkoutAndLoad(store, product);

        assertThat(order.getShippingCost()).isEqualByComparingTo("2990");
        assertThat(order.getShippingMethodRef().getId())
                .as("guarda el método que de verdad se cobró, no el otro")
                .isEqualTo(cheap.method.getId());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Zone(ShippingZone zone, ShippingMethod method, ShippingRate rate) {
    }

    private Zone seedZone(Store store, String methodName, BigDecimal flatRate, boolean methodActive) {
        ShippingZone zone = new ShippingZone();
        zone.setStore(store);
        zone.setName("Región Metropolitana");
        zone.setRegions(List.of("RM"));
        zone.setCommunes(List.of("Santiago"));
        zone.setActive(true);
        zone = zoneRepository.save(zone);

        ShippingMethod method = new ShippingMethod();
        method.setStore(store);
        method.setName(methodName);
        method.setCode("metodo-" + nextSeq());
        method.setActive(methodActive);
        method = methodRepository.save(method);

        ShippingRate rate = new ShippingRate();
        rate.setStore(store);
        rate.setZone(zone);
        rate.setMethod(method);
        rate.setName("Tarifa plana");
        rate.setRateType(RateType.FLAT);
        rate.setFlatRate(flatRate);
        // La tarifa SÍ está activa: lo que se comprueba es el flag del método, que es el que no se miraba.
        rate.setActive(true);
        rate = rateRepository.save(rate);

        return new Zone(zone, method, rate);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder checkout(
            Store store, Product product) {
        return post("/api/v1/checkout")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("""
                        {
                          "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                          "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                          "region": "RM", "commune": "Santiago",
                          "items": [{"id":%d,"type":"product","quantity":1}],
                          "paymentMethod": "manual"
                        }
                        """.formatted(product.getId()));
    }

    private Order checkoutAndLoad(Store store, Product product) throws Exception {
        String response = mockMvc.perform(checkout(store, product))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return orderRepository.findById(objectMapper.readTree(response).get("orderId").asLong()).orElseThrow();
    }
}
