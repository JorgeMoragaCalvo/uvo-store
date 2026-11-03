package org.uvo.uvostore.order;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.CouponUsage;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.CouponType;
import org.uvo.uvostore.entity.shipping.ShippingMethod;
import org.uvo.uvostore.entity.shipping.ShippingRate;
import org.uvo.uvostore.entity.shipping.ShippingZone;
import org.uvo.uvostore.entity.shipping.enums.RateType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CouponRepository;
import org.uvo.uvostore.repository.CouponUsageRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.ShippingMethodRepository;
import org.uvo.uvostore.repository.ShippingRateRepository;
import org.uvo.uvostore.repository.ShippingZoneRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GAP-05. El IVA se calculaba sobre el subtotal antes del cupón y no se recalculaba después del descuento, y cada línea
 * guardaba IVA cero. Con precios sin IVA el comprador pagaba IVA sobre un importe que no pagó; con precios con IVA, la
 * orden y el documento POS declaraban IVA de más.
 *
 * <p>Cada caso pasa por el checkout real y verifica, además de los números, que las líneas suman la orden y que
 * {@code /cart/calculate} muestra lo mismo que se cobra. El envío queda fuera de la base del IVA, como estaba: es un
 * criterio que tiene que confirmar contabilidad.
 */
class TaxWithCouponTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponUsageRepository couponUsageRepository;
    @Autowired
    private ShippingZoneRepository zoneRepository;
    @Autowired
    private ShippingMethodRepository methodRepository;
    @Autowired
    private ShippingRateRepository rateRepository;

    @Test
    @DisplayName("Precios sin IVA: el IVA va sobre el subtotal ya descontado")
    void taxExclusivePricesTaxTheDiscountedAmount() throws Exception {
        // El ejemplo del informe: 10.000 con un cupón de 1.000 cobraba 10.900. Son 9.000 + 19 % = 10.710.
        Store store = store("gap05-excl", false);
        Product product = product(store, 10000);
        Coupon coupon = coupon(store, CouponType.FIXED, 1000);

        Order order = checkout(store, Map.of(product, 1), coupon.getCode());

        assertAmounts(order, false, "10000", "1000", "1710", "10710");
        assertLinesAddUp(order);
        assertQuoteMatches(store, Map.of(product, 1), coupon.getCode(), order);
    }

    @Test
    @DisplayName("Precios con IVA: el total no cambia y el IVA declarado es el contenido en lo pagado")
    void taxInclusivePricesDeclareTheContainedTax() throws Exception {
        // 11.900 con un cupón del 10 % sobre el neto: se pagan 10.900, como antes, pero el IVA contenido en 10.900
        // es 1.740 y no 1.900. El subtotal y el descuento quedan como los vio el comprador.
        Store store = store("gap05-incl", true);
        Product product = product(store, 11900);
        Coupon coupon = coupon(store, CouponType.PERCENTAGE, 10);

        Order order = checkout(store, Map.of(product, 1), coupon.getCode());

        assertAmounts(order, true, "11900", "1000", "1740", "10900");
        assertLinesAddUp(order);
        assertQuoteMatches(store, Map.of(product, 1), coupon.getCode(), order);
    }

    @Test
    @DisplayName("ORD-NDVV268K: el mismo total de 44.855, ahora con el IVA de lo que se pagó")
    void theRealOrderKeepsItsTotal() throws Exception {
        // 48.970 con IVA, cupón del 10 % sobre el neto de 41.151: 4.115. Se pagan 44.855 y el IVA contenido es
        // 44.855 − 37.693 = 7.162.
        Store store = store("gap05-real", true);
        Product product = product(store, 48970);
        Coupon coupon = coupon(store, CouponType.PERCENTAGE, 10);

        Order order = checkout(store, Map.of(product, 1), coupon.getCode());

        assertAmounts(order, true, "48970", "4115", "7162", "44855");
        assertLinesAddUp(order);
    }

    @Test
    @DisplayName("Sin cupón nada cambia: 10.000 + 19 % = 11.900")
    void withoutACouponNothingChanges() throws Exception {
        Store store = store("gap05-none", false);
        Product product = product(store, 10000);

        Order order = checkout(store, Map.of(product, 1), null);

        assertAmounts(order, false, "10000", "0", "1900", "11900");
        assertLinesAddUp(order);
    }

    @Test
    @DisplayName("Dos líneas, cupón porcentual y envío: las líneas suman la orden y el envío no paga IVA")
    void twoLinesWithShippingAddUp() throws Exception {
        // 9.990 + 3 × 4.990 = 24.960. El 10 % son 2.496, repartidos 999 y 1.497. La base es 22.464 y su IVA 4.268,
        // repartido 1.708 y 2.560 sobre 8.991 y 13.473. Con 3.990 de envío, el total es 22.464 + 4.268 + 3.990.
        Store store = storeShipping("gap05-lines", false);
        seedFlatShipping(store, 3990);
        Product first = product(store, 9990);
        Product second = product(store, 4990);
        Coupon coupon = coupon(store, CouponType.PERCENTAGE, 10);
        Map<Product, Integer> cart = Map.of(first, 1, second, 3);

        Order order = checkout(store, cart, coupon.getCode());

        assertAmounts(order, false, "24960", "2496", "4268", "30722");
        assertThat(order.getShippingCost()).isEqualByComparingTo("3990");
        assertLinesAddUp(order);
        OrderItem firstLine = line(order, first);
        OrderItem secondLine = line(order, second);
        assertThat(firstLine.getDiscountAmount()).isEqualByComparingTo("999");
        assertThat(firstLine.getTaxAmount()).isEqualByComparingTo("1708");
        assertThat(secondLine.getDiscountAmount()).isEqualByComparingTo("1497");
        assertThat(secondLine.getTaxAmount()).isEqualByComparingTo("2560");
        assertQuoteMatches(store, cart, coupon.getCode(), order);
    }

    @Test
    @DisplayName("El uso del cupón registra el descuento que entró en la orden, también con precios con IVA")
    void theCouponUsageRecordsTheOrderDiscount() throws Exception {
        // recordUsage recalculaba el descuento sobre order.subtotal. Con precios con IVA ese subtotal ahora es bruto
        // (11.900) y habría registrado 1.190 en vez de los 1.000 que se descontaron.
        Store store = store("gap05-usage", true);
        Product product = product(store, 11900);
        Coupon coupon = coupon(store, CouponType.PERCENTAGE, 10);

        Order order = checkout(store, Map.of(product, 1), coupon.getCode());

        List<CouponUsage> usages = couponUsageRepository.findByCouponId(coupon.getId());
        assertThat(usages).hasSize(1);
        assertThat(usages.getFirst().getDiscountAmount()).isEqualByComparingTo(order.getDiscountAmount());
    }

    // --- aserciones ------------------------------------------------------------------------------------------------

    private static void assertAmounts(Order order, boolean taxIncluded, String subtotal, String discount, String tax,
                                      String total) {
        assertThat(order.isTaxIncluded()).as("taxIncluded").isEqualTo(taxIncluded);
        assertThat(order.getSubtotal()).as("subtotal").isEqualByComparingTo(subtotal);
        assertThat(order.getDiscountAmount()).as("descuento").isEqualByComparingTo(discount);
        assertThat(order.getTaxAmount()).as("IVA").isEqualByComparingTo(tax);
        assertThat(order.getTotal()).as("total").isEqualByComparingTo(total);

        // La suma que corresponde a cada modo: con IVA incluido el IVA está dentro del subtotal y no se suma.
        BigDecimal sum = order.getSubtotal().subtract(order.getDiscountAmount()).add(order.getShippingCost());
        if (!taxIncluded) {
            sum = sum.add(order.getTaxAmount());
        }
        assertThat(sum).as("las partes de la orden dan el total").isEqualByComparingTo(order.getTotal());
    }

    private static void assertLinesAddUp(Order order) {
        List<OrderItem> items = order.getItems();
        assertThat(sum(items, OrderItem::getSubtotal)).as("Σ subtotal de las líneas").isEqualByComparingTo(order.getSubtotal());
        assertThat(sum(items, OrderItem::getDiscountAmount)).as("Σ descuento de las líneas")
                .isEqualByComparingTo(order.getDiscountAmount());
        assertThat(sum(items, OrderItem::getTaxAmount)).as("Σ IVA de las líneas").isEqualByComparingTo(order.getTaxAmount());
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getDiscountAmount().signum()).isNotNegative();
            assertThat(item.getTaxAmount().signum()).isNotNegative();
        });
    }

    /** Lo que el carrito muestra antes de pagar tiene que ser lo que el checkout cobra. */
    private void assertQuoteMatches(Store store, Map<Product, Integer> cart, String couponCode, Order order)
            throws Exception {
        String body = """
                {"items":[%s],"region":"RM","commune":"Santiago","couponCode":"%s"}
                """.formatted(itemsJson(cart), couponCode);
        String response = mockMvc.perform(post("/api/v1/cart/calculate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode quote = objectMapper.readTree(response);
        assertThat(quote.get("taxAmount").decimalValue()).as("IVA del carrito").isEqualByComparingTo(order.getTaxAmount());
        assertThat(quote.get("total").decimalValue()).as("total del carrito").isEqualByComparingTo(order.getTotal());
    }

    private static BigDecimal sum(List<OrderItem> items, java.util.function.Function<OrderItem, BigDecimal> amount) {
        return items.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static OrderItem line(Order order, Product product) {
        return order.getItems().stream()
                .filter(item -> item.getProduct().getId().equals(product.getId()))
                .findFirst().orElseThrow();
    }

    // --- fixtures --------------------------------------------------------------------------------------------------

    /** Una tienda que no envía, salvo los casos que siembran una tarifa con {@link #storeShipping}. */
    private Store store(String slug, boolean pricesIncludeTax) {
        Store store = storeShipping(slug, pricesIncludeTax);
        disableShipping(store);
        return store;
    }

    private Store storeShipping(String slug, boolean pricesIncludeTax) {
        Store store = createStore(slug);
        setSetting(store, "tax_rate", "19");
        setSetting(store, "prices_include_tax", String.valueOf(pricesIncludeTax));
        return store;
    }

    private Product product(Store store, int price) {
        return createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(price));
    }

    private Coupon coupon(Store store, CouponType type, int value) {
        Coupon coupon = new Coupon();
        coupon.setStore(store);
        coupon.setCode("GAP05-" + nextSeq());
        coupon.setName("Cupón GAP-05");
        coupon.setType(type);
        coupon.setValue(BigDecimal.valueOf(value));
        coupon.setActive(true);
        return couponRepository.save(coupon);
    }

    private void seedFlatShipping(Store store, int flatRate) {
        ShippingZone zone = new ShippingZone();
        zone.setStore(store);
        zone.setName("RM");
        zone.setRegions(List.of("RM"));
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
        rate.setFlatRate(BigDecimal.valueOf(flatRate));
        rate.setActive(true);
        rateRepository.save(rate);
    }

    private Order checkout(Store store, Map<Product, Integer> cart, String couponCode) throws Exception {
        String coupon = couponCode == null ? "" : "\"couponCode\": \"%s\",".formatted(couponCode);
        String body = """
                {
                  "customer": {"email":"a@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [%s],
                  %s
                  "paymentMethod": "manual"
                }
                """.formatted(itemsJson(cart), coupon);

        String response = mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return orderRepository.findById(objectMapper.readTree(response).get("orderId").asLong()).orElseThrow();
    }

    private static String itemsJson(Map<Product, Integer> cart) {
        return cart.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().getId()))
                .map(entry -> "{\"id\":%d,\"type\":\"product\",\"quantity\":%d}".formatted(entry.getKey().getId(), entry.getValue()))
                .collect(Collectors.joining(","));
    }
}
