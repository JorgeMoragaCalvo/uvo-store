package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.CouponType;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.ShippingRate;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.entity.shipping.enums.RateType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CouponRepository;
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
    @Autowired
    private CouponRepository couponRepository;

    // --- coverage endpoint ---------------------------------------------------------------------

    @Test
    @DisplayName("La cobertura expone las regiones y comunas de las zonas activas, con su nombre canónico")
    void coverageListsRegionsAndCommunesOfActiveZones() throws Exception {
        Store store = createStore("cov");
        seedZone(store, "Zona Centro", List.of("Metropolitana"), List.of("Santiago", "Providencia"), BigDecimal.valueOf(3990));

        mockMvc.perform(get("/api/v1/shipping/coverage").header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].region").value("Metropolitana de Santiago"))
                .andExpect(jsonPath("$[0].communes.length()").value(2))
                .andExpect(jsonPath("$[0].communes[0]").value("Providencia"))
                .andExpect(jsonPath("$[0].communes[1]").value("Santiago"));
    }

    @Test
    @DisplayName("Una zona sin comunas cubre la región entera y ofrece todas sus comunas")
    void aZoneWithoutCommunesCoversTheWholeRegion() throws Exception {
        Store store = createStore("cov-whole");
        seedZone(store, "Todo Valparaíso", List.of("Valparaíso"), null, BigDecimal.valueOf(4990));

        mockMvc.perform(get("/api/v1/shipping/coverage").header("Host", hostHeader(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].region").value("Valparaíso"))
                .andExpect(jsonPath("$[0].communes.length()").value(38))
                .andExpect(jsonPath("$[0].communes[0]").value("Algarrobo"));
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
        assertThat(order.getShippingRegion()).isEqualTo("Metropolitana de Santiago");
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

    // --- zona de región entera tal como la guarda el panel ---------------------------------------

    @Test
    @DisplayName("Una zona guardada con comunas [] cubre cualquier comuna de su región al cobrar")
    void aWholeRegionZoneSavedAsEmptyListMatchesAnyCommune() throws Exception {
        // El panel guarda [] —no null— cuando el campo de comunas queda en blanco. La cobertura lo leía
        // como región entera y el cálculo no: con una comuna en la solicitud, la zona no servía.
        Store store = createStore("ship-whole-empty");
        setSetting(store, "tax_rate", "0");
        seedZone(store, "Todo Valparaíso", List.of("Valparaíso"), List.of(), BigDecimal.valueOf(4990));
        Category category = createCategory(store, "Cat");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));

        calculate(store, product, 1, "Valparaíso", "Viña del Mar", null)
                .andExpect(jsonPath("$.shippingAvailable").value(true))
                .andExpect(jsonPath("$.shippingCost").value(4990));
        calculate(store, product, 1, "Magallanes", "Punta Arenas", null)
                .andExpect(jsonPath("$.shippingAvailable").value(false));

        Order order = checkoutAndLoad(store, product, "Valparaíso", "Viña del Mar");
        assertThat(order.getShippingCost()).isEqualByComparingTo(BigDecimal.valueOf(4990));
    }

    // --- catálogo de comunas: zonas mezcladas y texto libre --------------------------------------

    @Test
    @DisplayName("En una región con zona entera y zona por comunas gana la más específica, no la primera por orden")
    void theMostSpecificZoneWinsInAMixedRegion() throws Exception {
        Store store = createStore("ship-mixed");
        setSetting(store, "tax_rate", "0");
        ShippingZone whole = seedZone(store, "Toda la RM", List.of("RM"), null, BigDecimal.valueOf(5990));
        ShippingZone santiago = seedZone(store, "Solo Santiago", List.of("RM"), List.of("Santiago"), BigDecimal.valueOf(2990));
        whole.setSortOrder(0);
        santiago.setSortOrder(1);
        zoneRepository.saveAll(List.of(whole, santiago));
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(1000));

        calculate(store, product, 1, "Metropolitana de Santiago", "Santiago", null)
                .andExpect(jsonPath("$.shippingCost").value(2990));
        calculate(store, product, 1, "Metropolitana de Santiago", "Puente Alto", null)
                .andExpect(jsonPath("$.shippingCost").value(5990));
        // Sin comuna ya no sirve la zona de Santiago: antes, esto cobraba la tarifa de Santiago a toda la región.
        calculateWithoutCommune(store, product, "Metropolitana de Santiago")
                .andExpect(jsonPath("$.shippingAvailable").value(true))
                .andExpect(jsonPath("$.shippingCost").value(5990));

        // La región se ofrece entera, así que el checkout pide comuna y puede elegir cualquiera de las 52.
        mockMvc.perform(get("/api/v1/shipping/coverage").header("Host", hostHeader(store)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].communes.length()").value(52));
    }

    @Test
    @DisplayName("Con solo zonas por comunas, una comuna no listada o sin comuna no tiene envío")
    void onlyNamedCommunesAreCoveredWithoutAWholeRegionZone() throws Exception {
        Store store = createStore("ship-named-only");
        seedZone(store, "Solo Santiago", List.of("RM"), List.of("Santiago"), BigDecimal.valueOf(2990));
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(1000));

        calculate(store, product, 1, "Metropolitana de Santiago", "Puente Alto", null)
                .andExpect(jsonPath("$.shippingAvailable").value(false));
        calculateWithoutCommune(store, product, "Metropolitana de Santiago")
                .andExpect(jsonPath("$.shippingAvailable").value(false));
    }

    @Test
    @DisplayName("Una zona antigua escrita a mano sigue cotizando con lo que manda el checkout")
    void aLegacyFreeTextZoneStillQuotes() throws Exception {
        Store store = createStore("ship-legacy");
        setSetting(store, "tax_rate", "0");
        seedZone(store, "Escrita a mano", List.of("Región Metropolitana"), List.of("santiago", "nunoa"), BigDecimal.valueOf(3990));
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(1000));

        calculate(store, product, 1, "Metropolitana de Santiago", "Ñuñoa", null)
                .andExpect(jsonPath("$.shippingAvailable").value(true))
                .andExpect(jsonPath("$.shippingCost").value(3990));
        mockMvc.perform(get("/api/v1/shipping/coverage").header("Host", hostHeader(store)))
                .andExpect(jsonPath("$[0].region").value("Metropolitana de Santiago"))
                .andExpect(jsonPath("$[0].communes[0]").value("Ñuñoa"))
                .andExpect(jsonPath("$[0].communes[1]").value("Santiago"));
    }

    @Test
    @DisplayName("Las comunas de una zona de dos regiones se leen por región: la otra queda entera")
    void communesAreReadPerRegion() throws Exception {
        Store store = createStore("ship-two-regions");
        setSetting(store, "tax_rate", "0");
        seedZone(store, "Centro", List.of("RM", "Valparaíso"), List.of("Santiago"), BigDecimal.valueOf(3990));
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(1000));

        calculate(store, product, 1, "Valparaíso", "Quilpué", null)
                .andExpect(jsonPath("$.shippingAvailable").value(true));
        calculate(store, product, 1, "Metropolitana de Santiago", "Puente Alto", null)
                .andExpect(jsonPath("$.shippingAvailable").value(false));
    }

    // --- envío gratis de la configuración general ------------------------------------------------

    @Test
    @DisplayName("Bajo el monto del envío gratis se cobra la tarifa")
    void belowTheFreeShippingThresholdTheRateIsCharged() throws Exception {
        Store store = freeShippingStore("free-below", "true");
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(1000));

        calculate(store, product, 2, "Metropolitana", "Santiago", null)
                .andExpect(jsonPath("$.shippingAvailable").value(true))
                .andExpect(jsonPath("$.shippingCost").value(3990));
    }

    @Test
    @DisplayName("Desde el monto del envío gratis el despacho es cero y la orden conserva el método")
    void fromTheFreeShippingThresholdShippingIsFree() throws Exception {
        Store store = freeShippingStore("free-reached", "true");
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(3000));

        calculate(store, product, 2, "Metropolitana", "Santiago", null)
                .andExpect(jsonPath("$.shippingAvailable").value(true))
                .andExpect(jsonPath("$.shippingCost").value(0));

        Order order = checkoutAndLoad(store, product, "Metropolitana", "Santiago");
        assertThat(order.getShippingCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getTotal()).isEqualByComparingTo(BigDecimal.valueOf(6000));
        assertThat(order.getShippingMethod()).isEqualTo("Despacho a domicilio");
    }

    @Test
    @DisplayName("Con el envío gratis apagado el monto no cuenta")
    void theThresholdIsIgnoredWhileFreeShippingIsOff() throws Exception {
        Store store = freeShippingStore("free-off", "false");
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(3000));

        calculate(store, product, 2, "Metropolitana", "Santiago", null)
                .andExpect(jsonPath("$.shippingCost").value(3990));
    }

    @Test
    @DisplayName("El envío gratis no da cobertura a una dirección que ninguna zona cubre")
    void freeShippingDoesNotCoverAnUncoveredAddress() throws Exception {
        Store store = freeShippingStore("free-uncovered", "true");
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(3000));

        calculate(store, product, 2, "Magallanes", "Punta Arenas", null)
                .andExpect(jsonPath("$.shippingAvailable").value(false));
    }

    @Test
    @DisplayName("El monto del envío gratis se compara antes del cupón, igual que en las tarifas")
    void theFreeShippingThresholdIsCheckedBeforeTheCoupon() throws Exception {
        Store store = freeShippingStore("free-coupon", "true");
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(3000));
        Coupon coupon = new Coupon();
        coupon.setStore(store);
        coupon.setCode("MENOS-" + nextSeq());
        coupon.setName("2000 de descuento");
        coupon.setType(CouponType.FIXED);
        coupon.setValue(BigDecimal.valueOf(2000));
        coupon.setActive(true);
        couponRepository.save(coupon);

        // 6000 de productos, 4000 después del cupón: sigue sobre el umbral de 5000 porque se mide antes.
        calculate(store, product, 2, "Metropolitana", "Santiago", coupon.getCode())
                .andExpect(jsonPath("$.couponApplied").value(true))
                .andExpect(jsonPath("$.shippingCost").value(0))
                .andExpect(jsonPath("$.total").value(4000));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private Store freeShippingStore(String slug, String freeShippingEnabled) {
        Store store = createStore(slug);
        setSetting(store, "tax_rate", "0");
        setSetting(store, "free_shipping_enabled", freeShippingEnabled);
        setSetting(store, "free_shipping_threshold", "5000");
        seedZone(store, "Zona Centro", List.of("Metropolitana"), List.of("Santiago"), BigDecimal.valueOf(3990));
        return store;
    }

    private ResultActions calculate(Store store, Product product, int quantity, String region, String commune,
                                    String couponCode) throws Exception {
        String coupon = couponCode == null ? "" : ",\"couponCode\":\"%s\"".formatted(couponCode);
        String body = """
                {"items":[{"id":%d,"type":"product","quantity":%d}],"region":"%s","commune":"%s"%s}
                """.formatted(product.getId(), quantity, region, commune, coupon);
        return mockMvc.perform(post("/api/v1/cart/calculate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
    }

    private ResultActions calculateWithoutCommune(Store store, Product product, String region) throws Exception {
        String body = """
                {"items":[{"id":%d,"type":"product","quantity":1}],"region":"%s"}
                """.formatted(product.getId(), region);
        return mockMvc.perform(post("/api/v1/cart/calculate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
    }

    private Order checkoutAndLoad(Store store, Product product, String region, String commune) throws Exception {
        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(checkoutBody(product.getId(), region, commune)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(response).get("orderId").asLong();
        return orderRepository.findById(orderId).orElseThrow();
    }

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
