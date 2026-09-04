package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A7 — the money bug. Every order used to ship for $0: the SPA never sent a region, so
 * {@code ShippingRateServiceImpl.findZone(null, null)} matched nothing and the cost fell through to
 * {@code .orElse(ZERO)} without a word. These tests pin down both halves of the fix — the coverage
 * a customer can actually choose from, and that an address outside it is refused instead of
 * shipped free.
 */
class ShippingCoverageTest extends IntegrationTestSupport {

    @Autowired
    private ShippingZoneRepository zoneRepository;
    @Autowired
    private ShippingMethodRepository methodRepository;
    @Autowired
    private ShippingRateRepository rateRepository;
    @Autowired
    private OrderRepository orderRepository;

    // --- coverage endpoint ---------------------------------------------------------------------

    @Test
    @DisplayName("La cobertura expone las regiones y comunas de las zonas activas")
    void coverageListsRegionsAndCommunesOfActiveZones() throws Exception {
        Store store = createStore("cov");
        seedZone(store, "Zona Centro", List.of("Metropolitana"), List.of("Santiago", "Providencia"), BigDecimal.valueOf(3990));

        mockMvc.perform(get("/api/v1/shipping/coverage").header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].region").value("Metropolitana"))
                .andExpect(jsonPath("$[0].communes[0]").value("Santiago"))
                .andExpect(jsonPath("$[0].communes[1]").value("Providencia"));
    }

    @Test
    @DisplayName("Una zona sin comunas cubre la región entera y devuelve la lista vacía")
    void aZoneWithoutCommunesCoversTheWholeRegion() throws Exception {
        Store store = createStore("cov-whole");
        seedZone(store, "Todo Valparaíso", List.of("Valparaíso"), null, BigDecimal.valueOf(4990));

        mockMvc.perform(get("/api/v1/shipping/coverage").header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].region").value("Valparaíso"))
                .andExpect(jsonPath("$[0].communes").isEmpty());
    }

    @Test
    @DisplayName("Una zona inactiva no aparece en la cobertura")
    void inactiveZonesAreExcluded() throws Exception {
        Store store = createStore("cov-inactive");
        ShippingZone zone = seedZone(store, "Zona apagada", List.of("Antofagasta"), null, BigDecimal.TEN);
        zone.setActive(false);
        zoneRepository.save(zone);

        mockMvc.perform(get("/api/v1/shipping/coverage").header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("La cobertura de una tienda no se filtra a otra")
    void coverageIsScopedToTheStore() throws Exception {
        Store storeA = createStore("cov-a");
        Store storeB = createStore("cov-b");
        seedZone(storeA, "Solo de A", List.of("Metropolitana"), null, BigDecimal.TEN);

        mockMvc.perform(get("/api/v1/shipping/coverage").header("Host", hostHeader(storeB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // --- el bug de dinero ------------------------------------------------------------------------

    @Test
    @DisplayName("Un checkout con región y comuna cubiertas cobra la tarifa de la zona, no cero")
    void checkoutWithCoveredAddressChargesTheZoneRate() throws Exception {
        Store store = createStore("ship-charged");
        setSetting(store, "tax_rate", "0");
        seedZone(store, "Zona Centro", List.of("Metropolitana"), List.of("Santiago"), BigDecimal.valueOf(3990));
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));

        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(checkoutBody(product.getId(), "Metropolitana", "Santiago")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long orderId = objectMapper.readTree(response).get("orderId").asLong();
        Order order = orderRepository.findById(orderId).orElseThrow();

        // This is the regression: before the fix it was 0, on every single order.
        assertThat(order.getShippingCost()).isEqualByComparingTo(BigDecimal.valueOf(3990));
        assertThat(order.getShippingRegion()).isEqualTo("Metropolitana");
        assertThat(order.getShippingCommune()).isEqualTo("Santiago");
        assertThat(order.getTotal()).isEqualByComparingTo(BigDecimal.valueOf(2000 + 3990));
    }

    @Test
    @DisplayName("Un checkout a una comuna sin cobertura se rechaza con 409, no se cobra envío cero")
    void checkoutOutsideCoverageIsRejected() throws Exception {
        Store store = createStore("ship-refused");
        seedZone(store, "Zona Centro", List.of("Metropolitana"), List.of("Santiago"), BigDecimal.valueOf(3990));
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));

        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(checkoutBody(product.getId(), "Magallanes", "Punta Arenas")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("No hay envío disponible")));

        assertThat(orderRepository.findAll().stream()
                .filter(o -> o.getStore().getId().equals(store.getId()))
                .toList())
                .as("no debe crearse ninguna orden")
                .isEmpty();
    }

    @Test
    @DisplayName("Sin región tampoco se crea la orden: es el caso exacto que el SPA producía")
    void checkoutWithoutARegionIsRejected() throws Exception {
        Store store = createStore("ship-noregion");
        seedZone(store, "Zona Centro", List.of("Metropolitana"), List.of("Santiago"), BigDecimal.valueOf(3990));
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));

        mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(checkoutBody(product.getId(), null, null)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("El cálculo del carrito distingue 'no llegamos ahí' de 'envío gratis'")
    void calculateReportsShippingAvailability() throws Exception {
        Store store = createStore("ship-calc");
        seedZone(store, "Zona Centro", List.of("Metropolitana"), List.of("Santiago"), BigDecimal.valueOf(3990));
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));

        String covered = """
                {"items":[{"id":%d,"type":"product","quantity":1}],"region":"Metropolitana","commune":"Santiago"}
                """.formatted(product.getId());
        mockMvc.perform(post("/api/v1/cart/calculate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json").content(covered))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingAvailable").value(true))
                .andExpect(jsonPath("$.shippingCost").value(3990));

        String uncovered = """
                {"items":[{"id":%d,"type":"product","quantity":1}],"region":"Magallanes","commune":"Punta Arenas"}
                """.formatted(product.getId());
        mockMvc.perform(post("/api/v1/cart/calculate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json").content(uncovered))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingAvailable").value(false));
    }

    @Test
    @DisplayName("Un cupón inválido se distingue de no haber puesto ninguno")
    void calculateReportsWhetherTheCouponWasApplied() throws Exception {
        Store store = createStore("coupon-signal");
        disableShipping(store);
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));

        String body = """
                {"items":[{"id":%d,"type":"product","quantity":1}],"couponCode":"NO-EXISTE"}
                """.formatted(product.getId());

        mockMvc.perform(post("/api/v1/cart/calculate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponApplied").value(false))
                .andExpect(jsonPath("$.discountAmount").value(0));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private ShippingZone seedZone(Store store, String name, List<String> regions, List<String> communes, BigDecimal flatRate) {
        ShippingZone zone = new ShippingZone();
        zone.setStore(store);
        zone.setName(name);
        zone.setRegions(regions);
        zone.setCommunes(communes);
        zone.setActive(true);
        zone = zoneRepository.save(zone);

        ShippingMethod method = new ShippingMethod();
        method.setStore(store);
        method.setName("Despacho a domicilio");
        method.setCode("domicilio-" + nextSeq());
        method.setActive(true);
        method = methodRepository.save(method);

        ShippingRate rate = new ShippingRate();
        rate.setStore(store);
        rate.setZone(zone);
        rate.setMethod(method);
        rate.setName("Tarifa plana");
        rate.setRateType(RateType.FLAT);
        rate.setFlatRate(flatRate);
        rate.setActive(true);
        rateRepository.save(rate);

        return zone;
    }

    private String checkoutBody(Long productId, String region, String commune) {
        String regionJson = region == null ? "null" : "\"" + region + "\"";
        String communeJson = commune == null ? "null" : "\"" + commune + "\"";
        return """
                {
                  "customer": {"email":"comprador@test.local","firstName":"Test","lastName":"Comprador","phone":"+56900000000"},
                  "shippingAddress": {"addressLine1":"Calle 1 #123","city":"Santiago","state":"Metropolitana","postalCode":"8320000","country":"CL"},
                  "region": %s,
                  "commune": %s,
                  "items": [{"id": %d, "type": "product", "quantity": 2}],
                  "paymentMethod": "manual"
                }
                """.formatted(regionJson, communeJson, productId);
    }
}
