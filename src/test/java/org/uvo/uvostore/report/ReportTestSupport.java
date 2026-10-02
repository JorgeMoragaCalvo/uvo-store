package org.uvo.uvostore.report;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.uvo.uvostore.entity.catalog.Category;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.OrderRefund;
import org.uvo.uvostore.entity.order.enums.FulfillmentStatus;
import org.uvo.uvostore.entity.order.enums.OrderStatus;
import org.uvo.uvostore.entity.order.enums.PaymentStatus;
import org.uvo.uvostore.entity.order.enums.RefundStatus;
import org.uvo.uvostore.entity.order.enums.RefundType;
import org.uvo.uvostore.entity.order.enums.PaymentMethodType;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRefundRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.support.IntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Base de los tests de informes. Los tres servicios de informes no tenían <b>ningún</b> test antes de
 * F20, que es en buena medida por qué sus defectos de cifras llegaron tan lejos.
 *
 * <p>Las órdenes se construyen a mano en vez de pasar por el checkout porque hace falta fijar
 * {@code createdAt} —el caso de la medianoche chilena no se puede provocar de otra forma— y porque un
 * checkout real arrastra stock, cupones y eventos que no tienen nada que ver con lo que se mide aquí.
 */
abstract class ReportTestSupport extends IntegrationTestSupport {

    @Autowired
    protected OrderRepository orderRepository;
    @Autowired
    protected OrderRefundRepository refundRepository;
    @PersistenceContext
    protected EntityManager entityManager;

    protected Order paidOrder(Store store, Product product, BigDecimal total, Instant createdAt) {
        return paidOrder(store, List.of(new Line(product, 1, total)), total, BigDecimal.ZERO, createdAt);
    }

    protected record Line(Product product, int quantity, BigDecimal unitPrice) {
    }

    protected Order paidOrder(Store store, List<Line> lines, BigDecimal total, BigDecimal discount, Instant createdAt) {
        Order order = new Order();
        order.setStore(store);
        order.setOrderNumber("ORD-REP-" + nextSeq());
        order.setCustomerEmail("comprador@test.local");
        order.setCustomerFirstName("Test");
        order.setCustomerLastName("Comprador");
        BigDecimal subtotal = lines.stream()
                .map(l -> l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setSubtotal(subtotal);
        order.setDiscountAmount(discount);
        order.setShippingCost(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotal(total);
        order.setStatus(OrderStatus.PROCESSING);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentMethod(PaymentMethodType.MANUAL);
        order.setFulfillmentStatus(FulfillmentStatus.UNFULFILLED);

        for (Line line : lines) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(line.product());
            item.setProductName(line.product().getName());
            item.setProductSku(line.product().getSku());
            item.setQuantity(line.quantity());
            item.setPrice(line.unitPrice());
            item.setSubtotal(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
            item.setTaxAmount(BigDecimal.ZERO);
            order.getItems().add(item);
        }

        Order saved = orderRepository.save(order);
        // createdAt lo pone @CreationTimestamp, así que la fecha que el test necesita se fija después y
        // por SQL directo: es la única forma de colocar una venta a las 22:30 de Chile.
        if (createdAt != null) {
            entityManager.createNativeQuery("UPDATE orders SET created_at = :createdAt WHERE id = :id")
                    .setParameter("createdAt", createdAt)
                    .setParameter("id", saved.getId())
                    .executeUpdate();
            entityManager.detach(saved);
            return orderRepository.findById(saved.getId()).orElseThrow();
        }
        return saved;
    }

    /**
     * Los servicios de informes resuelven la tienda con {@code TenantContext.requireStoreId()}, y aquí se
     * les llama directamente en vez de por HTTP. {@code TenantContext.runWithin} no sirve porque recibe un
     * {@code Runnable} y estos métodos devuelven valor.
     */
    protected <T> T inStore(Store store, java.util.function.Supplier<T> action) {
        TenantContext.set(store);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    protected void refund(Order order, BigDecimal amount, RefundStatus status) {
        refundRepository.save(OrderRefund.builder()
                .order(order)
                .amount(amount)
                .type(RefundType.EXTERNAL)
                .status(status)
                .reason("test")
                .build());
    }
}
