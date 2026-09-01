package org.uvo.uvostore.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.order.Coupon;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.enums.CouponType;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.CouponRepository;
import org.uvo.uvostore.repository.CouponUsageRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.order.CouponService;
import org.uvo.uvostore.service.order.OrderInventoryService;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// C5. Exercises OrderInventoryService directly rather than going through markPaid: this base class
// runs every test inside a transaction that is rolled back, so the AFTER_COMMIT listener that
// normally triggers the decrement never fires (see IntegrationTestSupport's class comment). All the
// logic worth testing lives in the service anyway — the listener is now a thin delegation.
class OrderInventoryTest extends IntegrationTestSupport {

    @Autowired
    private OrderInventoryService orderInventoryService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponUsageRepository couponUsageRepository;
    @Autowired
    private CouponService couponService;

    @Test
    @DisplayName("El stock se descuenta exactamente una vez al aplicar el pago")
    void stockIsDecrementedOnce() {
        Store store = createStore("inv");
        Product product = productWithStock(store, 10);
        Order order = orderFor(store, product, 3);

        orderInventoryService.applyOrderStock(order);

        assertThat(reloadStock(product)).isEqualTo(7);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().isStockApplied()).isTrue();
    }

    @Test
    @DisplayName("Aplicar el stock dos veces no descuenta dos veces")
    void applyingStockTwiceIsIdempotent() {
        Store store = createStore("inv");
        Product product = productWithStock(store, 10);
        Order order = orderFor(store, product, 3);

        orderInventoryService.applyOrderStock(order);
        orderInventoryService.applyOrderStock(order);

        assertThat(reloadStock(product)).isEqualTo(7);
    }

    @Test
    @DisplayName("Sin stock suficiente no baja de cero y queda nota en el historial de la orden")
    void insufficientStockLeavesANoteAndNeverGoesNegative() {
        Store store = createStore("inv");
        Product product = productWithStock(store, 2);
        Order order = orderFor(store, product, 5);

        orderInventoryService.applyOrderStock(order);

        assertThat(reloadStock(product)).isEqualTo(2);
        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatusHistory())
                .anyMatch(h -> h.getNotes() != null && h.getNotes().contains("Stock insuficiente al confirmar el pago"));
    }

    @Test
    @DisplayName("Un producto con manageStock=false no se descuenta")
    void unmanagedStockIsLeftAlone() {
        Store store = createStore("inv");
        Product product = productWithStock(store, 10);
        product.setManageStock(false);
        productRepository.save(product);
        Order order = orderFor(store, product, 3);

        orderInventoryService.applyOrderStock(order);

        assertThat(reloadStock(product)).isEqualTo(10);
    }

    @Test
    @DisplayName("Cancelar una orden pagada devuelve el stock; cancelarla de nuevo no lo devuelve dos veces")
    void cancellingRestoresStockOnlyOnce() {
        Store store = createStore("inv");
        Product product = productWithStock(store, 10);
        Order order = orderFor(store, product, 4);

        orderInventoryService.applyOrderStock(order);
        assertThat(reloadStock(product)).isEqualTo(6);

        orderInventoryService.restoreOrderStock(order);
        assertThat(reloadStock(product)).isEqualTo(10);

        orderInventoryService.restoreOrderStock(order);
        assertThat(reloadStock(product)).isEqualTo(10);
    }

    @Test
    @DisplayName("Cancelar una orden que nunca se pagó no inventa stock")
    void cancellingAnUnpaidOrderRestoresNothing() {
        Store store = createStore("inv");
        Product product = productWithStock(store, 10);
        Order order = orderFor(store, product, 4);

        orderInventoryService.restoreOrderStock(order);

        assertThat(reloadStock(product)).isEqualTo(10);
    }

    @Test
    @DisplayName("claimUsage respeta usage_limit: el segundo intento no incrementa")
    void claimUsageEnforcesTheLimit() {
        Store store = createStore("inv");
        Coupon coupon = singleUseCoupon(store);

        assertThat(couponService.claimUsage(coupon)).isTrue();
        assertThat(couponService.claimUsage(coupon)).isFalse();
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getTimesUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Un cupón sin usage_limit se puede reclamar sin tope")
    void unlimitedCouponAlwaysClaims() {
        Store store = createStore("inv");
        Coupon coupon = singleUseCoupon(store);
        coupon.setUsageLimit(null);
        couponRepository.save(coupon);

        assertThat(couponService.claimUsage(coupon)).isTrue();
        assertThat(couponService.claimUsage(coupon)).isTrue();
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getTimesUsed()).isEqualTo(2);
    }

    @Test
    @DisplayName("Cancelar devuelve el uso del cupón; hacerlo dos veces no lo devuelve dos veces")
    void releasingCouponUsageIsIdempotent() {
        Store store = createStore("inv");
        Product product = productWithStock(store, 10);
        Coupon coupon = singleUseCoupon(store);
        Customer customer = createCustomer(store, "cupon");

        Order order = orderFor(store, product, 1);
        order.setCoupon(coupon);
        orderRepository.save(order);
        couponService.claimUsage(coupon);
        couponService.recordUsage(coupon, order, customer);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getTimesUsed()).isEqualTo(1);

        orderInventoryService.releaseCouponUsage(order);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getTimesUsed()).isZero();
        assertThat(couponUsageRepository.findByCouponId(coupon.getId())).isEmpty();

        orderInventoryService.releaseCouponUsage(order);
        assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getTimesUsed()).isZero();
    }

    private int reloadStock(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private Product productWithStock(Store store, int stock) {
        TenantContext.set(store);
        Category category = createCategory(store, "Inventario");
        Product product = createProduct(store, category, "Producto", BigDecimal.valueOf(1000));
        product.setStock(stock);
        return productRepository.save(product);
    }

    private Coupon singleUseCoupon(Store store) {
        Coupon coupon = new Coupon();
        coupon.setStore(store);
        coupon.setCode("CUPON-" + nextSeq());
        coupon.setName("Cupón de prueba");
        coupon.setType(CouponType.FIXED);
        coupon.setValue(BigDecimal.valueOf(500));
        coupon.setUsageLimit(1);
        coupon.setActive(true);
        return couponRepository.save(coupon);
    }

    private Order orderFor(Store store, Product product, int quantity) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-TEST-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        order.setSubtotal(BigDecimal.valueOf(1000));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(BigDecimal.valueOf(1000));
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setProductName(product.getName());
        item.setProductSku(product.getSku());
        item.setQuantity(quantity);
        item.setPrice(product.getPrice());
        item.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        item.setTaxAmount(BigDecimal.ZERO);

        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        order.setItems(items);
        return orderRepository.save(order);
    }
}
