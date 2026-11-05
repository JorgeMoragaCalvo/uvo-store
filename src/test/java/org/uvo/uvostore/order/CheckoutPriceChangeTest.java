package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.CouponType;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CouponRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GAP-07. Lo que cambia entre que el comprador ve el total y confirma: precio, oferta, variante y el reintento de
 * una orden pendiente.
 *
 * <p>El checkout recotizaba y cobraba el precio de ese momento sin compararlo con nada: si una oferta vencía o el
 * POS subía un precio mientras el comprador llenaba sus datos, se cobraba la diferencia sin avisar. Ahora el SPA
 * manda el total que mostró ({@code expectedTotal}) y el checkout cobra ese o responde 409. Los precios se cambian
 * aquí con el repositorio; el POS y el panel escriben las mismas columnas.
 *
 * <p>Cuándo se respeta el precio congelado: una orden pendiente se reutiliza solo si su total es el que el
 * comprador ve. Si no, se cobra el vigente y la vieja se reemplaza.
 */
class CheckoutPriceChangeTest extends IntegrationTestSupport {

    private static final String REPLACED = "Reemplazada por una orden con el precio vigente";

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private ProductVariationRepository variationRepository;

    @Test
    @DisplayName("Un precio que sube entre la cotización y la confirmación no se cobra sin aceptarlo")
    void aPriceRiseIsNotChargedSilently() throws Exception {
        Store store = store("gap07-rise");
        Product product = product(store, 10000);

        product.setPrice(BigDecimal.valueOf(12000));
        productRepository.save(product);

        mockMvc.perform(checkout(store, line(product, 1), null, 10000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "El total de tu pedido cambió de $10.000 a $12.000. Revisa el carrito y confirma de nuevo."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
        assertThat(ordersOf(store)).isZero();

        // Con el total nuevo a la vista, la compra pasa.
        mockMvc.perform(checkout(store, line(product, 1), null, 12000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(12000));
    }

    @Test
    @DisplayName("Una oferta que vence o que empieza también cambia el total, y también hay que aceptarlo")
    void anOfferThatEndsOrStartsNeedsANewAcceptance() throws Exception {
        Store store = store("gap07-offer");
        Product ending = product(store, 10000);
        putOnSale(ending, 7990, Instant.now().plus(1, ChronoUnit.HOURS));
        Product starting = product(store, 10000);

        ending.setSaleEndsAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        productRepository.save(ending);
        mockMvc.perform(checkout(store, line(ending, 1), null, 7990))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "El total de tu pedido cambió de $7.990 a $10.000. Revisa el carrito y confirma de nuevo."));

        // Bajar también es un cambio: el comprador confirma lo que ve, ni más ni menos.
        putOnSale(starting, 7990, null);
        mockMvc.perform(checkout(store, line(starting, 1), null, 10000))
                .andExpect(status().isConflict());

        assertThat(ordersOf(store)).isZero();
    }

    @Test
    @DisplayName("Sin el total visto, un cliente anterior de la API compra como antes, al precio vigente")
    void withoutTheExpectedTotalNothingChanges() throws Exception {
        Store store = store("gap07-compat");
        Product product = product(store, 10000);
        product.setPrice(BigDecimal.valueOf(12000));
        productRepository.save(product);

        mockMvc.perform(checkout(store, line(product, 1), null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(12000));
    }

    @Test
    @DisplayName("Reintento con el mismo total: es la misma orden, como en F15")
    void aRetryWithTheSameTotalReusesTheOrder() throws Exception {
        Store store = store("gap07-same");
        Product product = product(store, 10000);

        long first = orderIdOf(checkout(store, line(product, 1), null, 10000));
        long second = orderIdOf(checkout(store, line(product, 1), null, 10000));

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("Reintento después de un cambio de precio: orden nueva al precio de hoy, y la vieja se cancela y devuelve el cupón")
    void aRetryAfterAPriceChangeReplacesTheOrder() throws Exception {
        Store store = store("gap07-replace");
        Product product = product(store, 10000);
        Coupon coupon = onePerCustomerCoupon(store);

        long first = orderIdOf(checkout(store, line(product, 1), coupon.getCode(), 9000));

        product.setPrice(BigDecimal.valueOf(12000));
        productRepository.save(product);
        String response = mockMvc.perform(checkout(store, line(product, 1), coupon.getCode(), 11000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11000))
                .andReturn().getResponse().getContentAsString();

        Order replaced = orderRepository.findById(first).orElseThrow();
        Order current = orderRepository.findById(objectMapper.readTree(response).get("orderId").asLong()).orElseThrow();
        assertThat(current.getId()).isNotEqualTo(first);
        assertThat(replaced.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(replaced.getStatusHistory()).anySatisfy(h -> assertThat(h.getNotes()).isEqualTo(REPLACED));
        // El cupón es de un uso por cliente: si la vieja no lo hubiera devuelto, la nueva se rechazaría con un 400.
        assertThat(current.getCoupon()).isNotNull();
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getTimesUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Si la orden vieja ya abrió una sesión de pago, no se toca: se podría estar pagando en otra pestaña")
    void anOrderWithAnOpenPaymentSessionIsLeftAlone() throws Exception {
        Store store = store("gap07-session");
        Product product = product(store, 10000);

        long first = orderIdOf(checkout(store, line(product, 1), null, 10000));
        Order opened = orderRepository.findById(first).orElseThrow();
        opened.setPaymentId("token-de-pasarela");
        orderRepository.save(opened);

        product.setPrice(BigDecimal.valueOf(12000));
        productRepository.save(product);
        long second = orderIdOf(checkout(store, line(product, 1), null, 12000));

        assertThat(second).isNotEqualTo(first);
        Order old = orderRepository.findById(first).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(old.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("Si el comprador sigue viendo el total de su orden pendiente, se le cobra ese: el precio congelado")
    void theFrozenPriceIsKeptWhileTheBuyerStillSeesIt() throws Exception {
        Store store = store("gap07-frozen");
        Product product = product(store, 10000);

        long first = orderIdOf(checkout(store, line(product, 1), null, 10000));
        product.setPrice(BigDecimal.valueOf(12000));
        productRepository.save(product);

        mockMvc.perform(checkout(store, line(product, 1), null, 10000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(first))
                .andExpect(jsonPath("$.total").value(10000));
    }

    @Test
    @DisplayName("Una variante desactivada (como la deja el POS) ya no se vende, ni en el carrito ni en el checkout")
    void aDeactivatedVariantIsRejected() throws Exception {
        Store store = store("gap07-inactive");
        ProductVariation variation = variation(store, product(store, 10000));
        variation.setActive(false);
        variationRepository.save(variation);

        mockMvc.perform(validate(store, variationLine(variation)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors['items.0']").value("Producto no disponible"));
        mockMvc.perform(checkout(store, variationLine(variation), null, 9990))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.fieldErrors['items.0']").value("Producto no disponible"));
        assertThat(ordersOf(store)).isZero();
    }

    @Test
    @DisplayName("Un carrito antiguo con una variación que ya no existe la marca en su línea")
    void anOldCartWithADeletedVariation() throws Exception {
        Store store = store("gap07-deleted");
        Product product = product(store, 10000);
        ProductVariation variation = variation(store, product);
        variationRepository.delete(variation);
        variationRepository.flush();

        mockMvc.perform(validate(store, line(product, 1), variationLine(variation)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors['items.0']").doesNotExist())
                .andExpect(jsonPath("$.errors['items.1']").value("Variación no encontrada"));
    }

    @Test
    @DisplayName("El carrito devuelve el precio vigente de cada línea, que es el que el SPA muestra")
    void theCartReturnsTheCurrentPrice() throws Exception {
        Store store = store("gap07-validate");
        Product product = product(store, 10000);
        putOnSale(product, 7990, null);

        mockMvc.perform(validate(store, line(product, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].price").value(7990));
    }

    @Test
    @DisplayName("Las líneas de la orden salen de la misma cotización que el total")
    void theLinesComeFromTheSameQuote() throws Exception {
        Store store = store("gap07-lines");
        Product onSale = product(store, 10000);
        putOnSale(onSale, 7990, null);
        Product regular = product(store, 5000);

        long id = orderIdOf(checkout(store, line(onSale, 3) + "," + line(regular, 1), null, 28970));

        Order order = orderRepository.findById(id).orElseThrow();
        assertThat(order.getItems()).extracting(OrderItem::getPrice)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(BigDecimal.valueOf(7990), BigDecimal.valueOf(5000));
        assertThat(order.getItems().stream().map(OrderItem::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(order.getSubtotal());
    }

    // --- fixtures ----------------------------------------------------------------------------------

    // Sin envío y sin IVA, para que el total sea la suma de los precios y los números se lean solos.
    private Store store(String slugPrefix) {
        Store store = createStore(slugPrefix);
        disableShipping(store);
        setSetting(store, "tax_rate", "0");
        return store;
    }

    private Product product(Store store, int price) {
        return createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(price));
    }

    private void putOnSale(Product product, int salePrice, Instant endsAt) {
        product.setOnSale(true);
        product.setSalePrice(BigDecimal.valueOf(salePrice));
        product.setSaleEndsAt(endsAt);
        productRepository.save(product);
    }

    private ProductVariation variation(Store store, Product product) {
        ProductVariation variation = new ProductVariation();
        variation.setStore(store);
        variation.setProduct(product);
        variation.setSku("VAR-" + nextSeq());
        variation.setPrice(BigDecimal.valueOf(9990));
        variation.setStock(5);
        return variationRepository.save(variation);
    }

    private Coupon onePerCustomerCoupon(Store store) {
        Coupon coupon = new Coupon();
        coupon.setStore(store);
        coupon.setCode("GAP07-" + nextSeq());
        coupon.setName("Cupón de un uso por cliente");
        coupon.setType(CouponType.FIXED);
        coupon.setValue(BigDecimal.valueOf(1000));
        coupon.setUsageLimitPerCustomer(1);
        coupon.setActive(true);
        return couponRepository.save(coupon);
    }

    private long ordersOf(Store store) {
        return orderRepository.findAll().stream()
                .filter(order -> order.getStore().getId().equals(store.getId()))
                .count();
    }

    private long orderIdOf(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("orderId").asLong();
    }

    private static String line(Product product, int quantity) {
        return "{\"id\":%d,\"type\":\"product\",\"quantity\":%d}".formatted(product.getId(), quantity);
    }

    private static String variationLine(ProductVariation variation) {
        return "{\"id\":%d,\"type\":\"variation\",\"quantity\":1}".formatted(variation.getId());
    }

    private MockHttpServletRequestBuilder validate(Store store, String... lines) {
        return post("/api/v1/cart/validate")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content("{\"items\":[%s]}".formatted(String.join(",", lines)));
    }

    // Siempre el mismo comprador: el reintento de F15 se busca entre las órdenes de este cliente.
    private MockHttpServletRequestBuilder checkout(Store store, String lines, String couponCode, Integer expectedTotal) {
        String coupon = couponCode == null ? "" : "\"couponCode\": \"%s\",".formatted(couponCode);
        String expected = expectedTotal == null ? "" : "\"expectedTotal\": %d,".formatted(expectedTotal);
        String body = """
                {
                  "customer": {"email":"gap07@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [%s],
                  %s
                  %s
                  "paymentMethod": "manual"
                }
                """.formatted(lines, coupon, expected);
        return post("/api/v1/checkout")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content(body);
    }
}
