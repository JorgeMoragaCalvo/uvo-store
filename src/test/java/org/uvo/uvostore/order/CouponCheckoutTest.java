package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.enums.CouponType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CouponRepository;
import org.uvo.uvostore.repository.CouponUsageRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F05. Un descuento solo existe si hay un cupón que lo respalde.
 *
 * <p>El fallo: el precio se calculaba validando el cupón con {@code customerId = null}, así que el
 * límite de usos por cliente no se evaluaba y el descuento entraba en el total. El checkout volvía a
 * validar —esa vez con el cliente de verdad—, y cuando el cupón resultaba no aplicable se limitaba a
 * <b>no adjuntarlo</b>: el total ya estaba rebajado. La orden quedaba con descuento, sin cupón, sin
 * {@code CouponUsage} y sin mover {@code times_used}.
 *
 * <p>Y no era un caso raro ni una carrera: era determinista y repetible. Quien ya hubiera gastado un
 * cupón de un uso por cliente se llevaba el descuento en <b>todas</b> sus compras siguientes, sin que
 * ningún contador ni ningún informe lo reflejara.
 */
class CouponCheckoutTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponUsageRepository couponUsageRepository;

    @Test
    @DisplayName("El primer uso de un cupón de un uso por cliente queda registrado")
    void theFirstUseIsRecorded() throws Exception {
        Store store = createStore("coupon-first");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        Coupon coupon = onePerCustomerCoupon(store);

        // 10.000 + 19 % de IVA − 1.000 de cupón. El IVA es el default cuando la tienda no configura
        // tax_rate, así que estos números son los de una tienda recién creada.
        mockMvc.perform(checkout(store, product, coupon.getCode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10900));

        Order order = lastOrderOf(store);
        assertThat(order.getCoupon()).as("el cupón queda atado a la orden").isNotNull();
        assertThat(order.getCouponCode()).isEqualTo(coupon.getCode());
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("1000");
        assertThat(couponUsageRepository.findByCouponId(coupon.getId())).hasSize(1);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getTimesUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("El segundo intento del mismo cliente se rechaza, no se cobra rebajado")
    void theSecondAttemptIsRejected() throws Exception {
        Store store = createStore("coupon-second");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        Coupon coupon = onePerCustomerCoupon(store);

        mockMvc.perform(checkout(store, product, coupon.getCode())).andExpect(status().isOk());
        long ordersAfterFirst = orderRepository.count();

        // Mismo email = mismo cliente (el checkout identifica por email, ver findOrCreateGuest).
        mockMvc.perform(checkout(store, product, coupon.getCode()))
                .andExpect(status().isBadRequest());

        assertThat(orderRepository.count())
                .as("no puede crearse una segunda orden con el descuento")
                .isEqualTo(ordersAfterFirst);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getTimesUsed())
                .as("y el contador no se mueve")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Ninguna orden queda con descuento y sin cupón: es la invariante del hallazgo")
    void noOrderEndsUpDiscountedWithoutACoupon() throws Exception {
        Store store = createStore("coupon-invariant");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        Coupon coupon = onePerCustomerCoupon(store);

        // Aquí no se comprueba el código de estado a propósito: lo que se afirma es la invariante, sea
        // cual sea la respuesta. Así este test sigue diciendo algo aunque alguien cambie la política de
        // rechazo — si el segundo checkout llegara a crear una orden, tendría que ser a precio completo.
        mockMvc.perform(checkout(store, product, coupon.getCode())).andExpect(status().isOk());
        mockMvc.perform(checkout(store, product, coupon.getCode()));

        List<Order> orders = orderRepository.findAll().stream()
                .filter(o -> o.getStore().getId().equals(store.getId()))
                .toList();
        assertThat(orders).isNotEmpty();
        assertThat(orders).allSatisfy(order -> {
            if (order.getDiscountAmount().signum() > 0) {
                assertThat(order.getCoupon())
                        .as("orden %s rebajada sin cupón", order.getOrderNumber())
                        .isNotNull();
                // Y el descuento tiene que estar contabilizado, no solo atado: el uso es lo que hace
                // avanzar el límite. Sin la fila, el mismo cupón se podría gastar indefinidamente.
                assertThat(couponUsageRepository.findByCouponId(order.getCoupon().getId()))
                        .as("orden %s con cupón pero sin uso registrado", order.getOrderNumber())
                        .isNotEmpty();
            }
        });
    }

    @Test
    @DisplayName("Un código que no existe no bloquea la compra: se paga precio completo")
    void anUnknownCodeStillLetsTheCheckoutThrough() throws Exception {
        // Sin regresión. El SPA guarda el código tecleado y lo manda igual (useCartStore), así que el
        // carrito ya mostraba precio completo: no hay divergencia que explicar y la venta sigue.
        Store store = createStore("coupon-unknown");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));

        mockMvc.perform(checkout(store, product, "NO-EXISTE-ESTE-CODIGO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11900)); // 10.000 + IVA, sin descuento

        Order order = lastOrderOf(store);
        assertThat(order.getCoupon()).isNull();
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("La cotización anónima del carrito sigue aplicando el cupón")
    void theAnonymousQuoteStillAppliesTheCoupon() throws Exception {
        // El carrito no sabe quién compra y no debe adivinarlo: muestra el mejor precio posible. Lo que
        // cambió es que el checkout ya no cobra otra cosa en silencio.
        Store store = createStore("coupon-quote");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        Coupon coupon = onePerCustomerCoupon(store);

        String body = """
                {"items":[{"id":%d,"type":"product","quantity":1}],"couponCode":"%s"}
                """.formatted(product.getId(), coupon.getCode());

        mockMvc.perform(post("/api/v1/cart/calculate")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponApplied").value(true))
                .andExpect(jsonPath("$.discountAmount").value(1000));
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private Coupon onePerCustomerCoupon(Store store) {
        Coupon coupon = new Coupon();
        coupon.setStore(store);
        coupon.setCode("UNOPORCLIENTE-" + nextSeq());
        coupon.setName("Cupón de un uso por cliente");
        coupon.setType(CouponType.FIXED);
        coupon.setValue(BigDecimal.valueOf(1000));
        coupon.setUsageLimitPerCustomer(1);
        coupon.setActive(true);
        return couponRepository.save(coupon);
    }

    private Order lastOrderOf(Store store) {
        return orderRepository.findAll().stream()
                .filter(o -> o.getStore().getId().equals(store.getId()))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder checkout(
            Store store, Product product, String couponCode) {
        String body = """
                {
                  "customer": {"email":"cliente-fijo@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM",
                  "commune": "Santiago",
                  "items": [{"id":%d,"type":"product","quantity":1}],
                  "couponCode": "%s",
                  "paymentMethod": "manual"
                }
                """.formatted(product.getId(), couponCode);
        return post("/api/v1/checkout")
                .header("Host", hostHeader(store))
                .contentType("application/json")
                .content(body);
    }
}
