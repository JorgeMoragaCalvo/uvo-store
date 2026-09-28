package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.enums.CouponType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CouponRepository;
import org.uvo.uvostore.repository.CouponUsageRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F15. Reintentar el pago no crea otra orden ni deja al cliente fuera.
 *
 * <p>El frontend crea la orden y solo después llama a la pasarela, así que un fallo al abrir la sesión de
 * pago deja una orden PENDING y el carrito intacto. Lo que pasaba en el siguiente clic:
 *
 * <ul>
 *   <li><b>Sin cupón</b>: otra orden pendiente, y otra más por cada intento.</li>
 *   <li><b>Con cupón</b>: nada — un 400. La reserva que hizo el primer intento agotaba el límite por
 *       cliente, así que el comprador se quedaba sin poder usar su descuento. Esta es la mitad grave, y
 *       el hallazgo la pone como secundaria.</li>
 * </ul>
 *
 * <p>Y esas órdenes no las miraba nadie: la conciliación de pagos exige id de pasarela, y a las que se
 * quedan por el camino les falta justamente eso.
 */
class CheckoutRetryTest extends IntegrationTestSupport {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponUsageRepository couponUsageRepository;

    @Test
    @DisplayName("Con cupón, el reintento devuelve la MISMA orden en vez de un 400")
    void aRetryWithACouponReusesTheSameOrder() throws Exception {
        Store store = createStore("retry-coupon");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        Coupon coupon = onePerCustomerCoupon(store);

        long firstId = orderIdOf(checkout(store, product, coupon.getCode(), 1));
        long ordersAfterFirst = orderRepository.count();

        long secondId = orderIdOf(checkout(store, product, coupon.getCode(), 1));

        assertThat(secondId).as("es el mismo pedido, no uno nuevo").isEqualTo(firstId);
        assertThat(orderRepository.count()).isEqualTo(ordersAfterFirst);
    }

    @Test
    @DisplayName("Y la reserva del cupón no se duplica ni se vuelve a reclamar")
    void theCouponReservationIsNotClaimedTwice() throws Exception {
        Store store = createStore("retry-reserve");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));
        Coupon coupon = onePerCustomerCoupon(store);

        checkout(store, product, coupon.getCode(), 1);
        checkout(store, product, coupon.getCode(), 1);
        checkout(store, product, coupon.getCode(), 1);

        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getTimesUsed())
                .as("times_used no puede subir por reintentar")
                .isEqualTo(1);
        assertThat(couponUsageRepository.findByCouponId(coupon.getId()))
                .as("una sola fila de uso")
                .hasSize(1);
    }

    @Test
    @DisplayName("Sin cupón, tampoco quedan dos órdenes pendientes")
    void aRetryWithoutACouponDoesNotLeaveTwoOrders() throws Exception {
        Store store = createStore("retry-nocoupon");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));

        long firstId = orderIdOf(checkout(store, product, null, 1));
        long secondId = orderIdOf(checkout(store, product, null, 1));

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    @DisplayName("Cambiar el carrito sí es una compra nueva")
    void changingTheCartCreatesANewOrder() throws Exception {
        // La condición que hace esto seguro: si el carrito no es el mismo, no es el mismo pedido.
        Store store = createStore("retry-changed");
        disableShipping(store);
        Product product = createProduct(store, createCategory(store, "Cat"), "Producto", BigDecimal.valueOf(10000));

        long firstId = orderIdOf(checkout(store, product, null, 1));
        long secondId = orderIdOf(checkout(store, product, null, 2));

        assertThat(secondId).isNotEqualTo(firstId);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private long orderIdOf(String response) throws Exception {
        return objectMapper.readTree(response).get("orderId").asLong();
    }

    private String checkout(Store store, Product product, String couponCode, int quantity) throws Exception {
        String coupon = couponCode == null ? "" : "\"couponCode\": \"%s\",".formatted(couponCode);
        String body = """
                {
                  "customer": {"email":"reintento@test.local","firstName":"A","lastName":"B","phone":"+56911111111"},
                  "shippingAddress": {"addressLine1":"Calle 1","city":"Santiago","state":"RM","postalCode":"8320000","country":"CL"},
                  "region": "RM", "commune": "Santiago",
                  "items": [{"id":%d,"type":"product","quantity":%d}],
                  %s
                  "paymentMethod": "manual"
                }
                """.formatted(product.getId(), quantity, coupon);

        return mockMvc.perform(post("/api/v1/checkout")
                        .header("Host", hostHeader(store))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Coupon onePerCustomerCoupon(Store store) {
        Coupon coupon = new Coupon();
        coupon.setStore(store);
        coupon.setCode("REINTENTO-" + nextSeq());
        coupon.setName("Cupón de un uso por cliente");
        coupon.setType(CouponType.FIXED);
        coupon.setValue(BigDecimal.valueOf(1000));
        coupon.setUsageLimitPerCustomer(1);
        coupon.setActive(true);
        return couponRepository.save(coupon);
    }
}
