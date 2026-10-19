package org.uvo.uvostore.service.order;

import io.sentry.Sentry;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.order.OrderStatusHistory;
import org.uvo.uvostore.repository.CouponUsageRepository;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OrderStatusHistoryRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

// C5: everything that moves stock or a coupon use because of an order's lifecycle lives here.
//
// It exists because four separate paths can cancel an order — OrderStatusService.markCancelled and
// .markPaymentFailed, AdminOrderService.cancelOrder and .updateStatus — and each of them would
// otherwise need its own copy of "was this order's stock ever applied? has it already been
// returned?". Getting that wrong doesn't lose data, it invents it: a second cancellation would add
// inventory that never existed.
//
// The stockApplied/stockRestored flags on the order (V13) are the source of truth for that, not the
// order's status, because status can be set to CANCELLED from several places and back again.
@Service
public class OrderInventoryService {

    private static final Logger log = LoggerFactory.getLogger(OrderInventoryService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final CouponService couponService;
    private final OrderStatusHistoryRepository statusHistoryRepository;

    public OrderInventoryService(OrderRepository orderRepository, ProductRepository productRepository,
                                 ProductVariationRepository variationRepository,
                                 CouponUsageRepository couponUsageRepository, CouponService couponService,
                                 OrderStatusHistoryRepository statusHistoryRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.couponUsageRepository = couponUsageRepository;
        this.couponService = couponService;
        this.statusHistoryRepository = statusHistoryRepository;
    }

    /**
     * Takes the order's items out of stock. Called once, when payment is confirmed.
     *
     * <p>A line that can't be decremented never aborts the rest of the order (the behaviour ported
     * from Laravel's queued listener), but it is no longer swallowed: every failure ends up in the
     * order's own status history and in Sentry, because by this point the customer has already paid
     * and somebody has to fix it by hand.
     *
     * <p>PROD-13a: la idempotencia ya no depende de la bandera en memoria. Leerla de la entidad y
     * escribirla al final dejaba pasar a dos llamadas simultáneas —el listener del pago y el barrido de
     * {@code StockRecoveryJob}—, y las dos descontaban. Ahora decide {@code claimStockApplication}, un
     * UPDATE condicional dentro de esta misma transacción: si algo falla después, el reclamo se deshace
     * con los descuentos.
     *
     * @return true si esta llamada aplicó el descuento; false si otra ya lo había hecho
     */
    @Transactional
    public boolean applyOrderStock(Order order) {
        // Atajo barato, no la guarda: una entidad leída antes puede decir false aunque otra transacción
        // ya la haya reclamado. Lo contrario no pasa — la bandera nunca vuelve a false.
        if (order.isStockApplied()) {
            log.info("Stock ya aplicado, se omite order_id={}", order.getId());
            return false;
        }
        if (orderRepository.claimStockApplication(order.getId()) == 0) {
            log.info("Stock ya aplicado por otra transacción, se omite order_id={}", order.getId());
            return false;
        }

        // PROD-13a: todo lo que el bucle va a leer, cargado ANTES del primer descuento. decrementStock
        // lleva clearAutomatically, así que tras la primera línea la orden y sus líneas salen de la sesión,
        // y el producto de la segunda (LAZY) ya no se puede inicializar: isManageStock() lanzaba
        // LazyInitializationException y se deshacía la transacción entera. En StockDecrementListener, que
        // lee la orden con findById, eso era cualquier orden con dos o más productos simples: pagada y sin
        // descontar nada, con solo una excepción en Sentry.
        order.getItems().forEach(item -> Hibernate.initialize(item.getProduct()));

        List<String> failures = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            if (item.getVariation() != null) {
                decrementVariation(item, failures);
            } else {
                decrementSimpleProduct(item, failures);
            }
        }

        // Sigue significando "esta orden ya pasó por el descuento" —de ahí la idempotencia de arriba—,
        // no "todas sus líneas se descontaron". Eso último lo dice ahora cada línea por su cuenta.
        //
        // PROD-13a: la base ya dice true por el reclamo, pero hay que repetirlo en la entidad. Order no
        // lleva @DynamicUpdate, así que el save de abajo escribe todas las columnas, y con la entidad en
        // false devolvería la bandera a false encima del reclamo.
        order.setStockApplied(true);
        if (!failures.isEmpty()) {
            reportStockFailure(order, failures);
        }
        orderRepository.save(order);
        return true;
    }

    /**
     * PROD-13a. La segunda oportunidad de una orden cobrada cuyo stock no se descontó: el proceso murió
     * entre el commit de {@code markPaid} y {@code StockDecrementListener}. La llama
     * {@code StockRecoveryJob}, desde otro bean, para que la transacción pase por el proxy.
     *
     * <p>Si el descuento se aplica aquí, es una anomalía y se dice: nota en el historial de la orden y
     * aviso a Sentry, la misma pareja que {@code reportStockFailure}. Si no, es que el listener llegó
     * primero, y no hay nada que contar.
     */
    @Transactional
    public void recoverMissedStock(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
        if (!applyOrderStock(order)) {
            return;
        }

        String detail = "Stock descontado por el barrido: el descuento tras el pago no llegó a ejecutarse";
        appendHistory(order, detail);
        log.error("{} order_id={} order_number={}", detail, orderId, order.getOrderNumber());
        Sentry.captureMessage(detail + " [order_number=" + order.getOrderNumber() + "]");
    }

    /**
     * Puts the order's items back into stock. Only does anything if the stock was actually taken
     * (i.e. the order reached PAID) and hasn't been returned yet — cancelling an unpaid order is a
     * no-op, because nothing was ever decremented.
     *
     * <p>F04: y devuelve <b>solo las líneas que se descontaron</b>. Antes devolvía todas en cuanto la
     * orden estaba marcada, y la marca de la orden se ponía aunque una línea hubiera fallado: quedaban
     * 1 de A y 0 de B, se descontaba A, fallaba B, y una cancelación sumaba 1 a cada una. La unidad de
     * B nunca existió. Restaurar de menos pierde inventario; restaurar de más lo inventa, y eso
     * termina en una venta que no se puede cumplir.
     */
    @Transactional
    public void restoreOrderStock(Order order) {
        if (!order.isStockApplied() || order.isStockRestored()) {
            return;
        }

        for (OrderItem item : order.getItems()) {
            if (!item.isStockApplied()) {
                continue;
            }
            if (item.getVariation() != null) {
                variationRepository.restoreStock(item.getVariation().getId(), item.getQuantity());
                productRepository.recalculateStockFromVariations(item.getProduct().getId());
            } else {
                // Ya no hace falta volver a preguntar por manageStock: si estuviera en false, la línea
                // no se descontó y no lleva la marca. Y así queda bien incluso si alguien lo cambia
                // entre el cobro y la cancelación, que antes descuadraba.
                productRepository.restoreStock(item.getProduct().getId(), item.getQuantity());
            }
        }

        order.setStockRestored(true);
        orderRepository.save(order);
        log.info("Stock restaurado por cancelación order_id={} order_number={}", order.getId(), order.getOrderNumber());
    }

    /**
     * Gives the coupon use back when an order is cancelled or its payment fails. The use is claimed
     * at checkout (it acts as a reservation, which is what actually enforces the limit), so without
     * this an abandoned cart would burn it forever.
     *
     * <p>Deleting the usage row is the idempotency guard: UNIQUE(coupon_id, order_id) guarantees at
     * most one, so a delete that affects no rows means the use was already returned.
     */
    @Transactional
    public void releaseCouponUsage(Order order) {
        if (order.getCoupon() == null) {
            return;
        }
        int deleted = couponUsageRepository.deleteByOrderId(order.getId());
        if (deleted == 0) {
            return;
        }
        couponService.releaseUsage(order.getCoupon());
        log.info("Uso de cupón devuelto order_id={} coupon_id={}", order.getId(), order.getCoupon().getId());
    }

    private void decrementVariation(OrderItem item, List<String> failures) {
        Long variationId = item.getVariation().getId();
        int updated = variationRepository.decrementStock(variationId, item.getQuantity());
        if (updated == 0) {
            failures.add("variación " + item.getProductSku() + " (id " + variationId + ", cantidad " + item.getQuantity() + ")");
            return;
        }
        // F04: la marca va aquí, pegada al único dato de fiar — que el UPDATE condicional afectó una
        // fila. Es lo que luego decide si esta línea se devuelve al cancelar.
        item.setStockApplied(true);
        // Parent Product.stock is the sum of its variations, so it has to follow. Recomputed in one
        // statement rather than through recalculateParentAggregate, which also touches minPrice and
        // needs TenantContext — neither belongs in an AFTER_COMMIT listener.
        productRepository.recalculateStockFromVariations(item.getProduct().getId());
    }

    private void decrementSimpleProduct(OrderItem item, List<String> failures) {
        if (!item.getProduct().isManageStock()) {
            return;
        }
        Long productId = item.getProduct().getId();
        int updated = productRepository.decrementStock(productId, item.getQuantity());
        if (updated == 0) {
            failures.add("producto " + item.getProductSku() + " (id " + productId + ", cantidad " + item.getQuantity() + ")");
            return;
        }
        item.setStockApplied(true);
    }

    private void reportStockFailure(Order order, List<String> failures) {
        String detail = "Stock insuficiente al confirmar el pago: " + String.join("; ", failures);

        // The order's own history, so whoever opens it in the admin panel sees the problem without
        // going anywhere else. The status is left untouched: the order IS paid, it just can't be
        // fulfilled as it stands.
        appendHistory(order, detail);

        log.error("{} order_id={} order_number={}", detail, order.getId(), order.getOrderNumber());
        // Explicit: this runs in a listener that deliberately doesn't throw, so it would never reach
        // GlobalExceptionHandler's Sentry reporting on its own.
        Sentry.captureMessage(detail + " [order_number=" + order.getOrderNumber() + "]");
    }

    /**
     * PROD-13a. Una nota en el historial de una orden que puede haber salido ya de la sesión.
     *
     * <p>Los descuentos ({@code decrementStock}) llevan {@code clearAutomatically}, así que cuando hay algo
     * que anotar la orden ya no está gestionada. Si su historial no se había cargado —que es lo que pasa
     * en {@code StockDecrementListener}, que la lee con {@code findById}— añadir a la colección lanza
     * {@code LazyInitializationException} y deshace la transacción entera: antes de esto, una venta sin
     * stock suficiente perdía la nota, perdía los descuentos de sus otras líneas y quedaba sin
     * {@code stock_applied}. Con el barrido además se reintentaría en cada corrida.
     *
     * <p>Si la colección <b>sí</b> está cargada (una orden recién creada en la misma transacción), se
     * sigue añadiendo a ella: guardar la fila aparte y luego hacer el {@code save} de la orden podría
     * borrarla por {@code orphanRemoval}, porque la colección separada no la contiene.
     */
    private void appendHistory(Order order, String notes) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setStatus(order.getStatus().name());
        history.setNotes(notes);
        if (Hibernate.isInitialized(order.getStatusHistory())) {
            history.setOrder(order);
            order.getStatusHistory().add(history);
        } else {
            history.setOrder(orderRepository.getReferenceById(order.getId()));
            statusHistoryRepository.save(history);
        }
    }
}
