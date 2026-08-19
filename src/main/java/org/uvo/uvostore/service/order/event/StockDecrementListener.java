package org.uvo.uvostore.service.order.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.service.catalog.ProductVariationService;

import java.util.NoSuchElementException;

// Ports ReduceStockOnPayment — runs AFTER_COMMIT on the transaction that confirmed payment
// (OrderStatusServiceImpl.markPaid), same trigger point as Laravel's queued listener on
// PaymentConfirmed. Each guard below (missing variation/product, insufficient stock, unmanaged
// stock) mirrors Laravel's "log and skip that line" behavior — a stock problem on one item never
// aborts the rest of the order's decrement.
@Component
public class StockDecrementListener {

    private static final Logger log = LoggerFactory.getLogger(StockDecrementListener.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final ProductVariationService productVariationService;

    public StockDecrementListener(OrderRepository orderRepository, ProductRepository productRepository,
                                   ProductVariationRepository variationRepository, ProductVariationService productVariationService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.productVariationService = productVariationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @org.springframework.transaction.annotation.Transactional
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new NoSuchElementException("Order " + event.orderId() + " not found"));

        log.info("Iniciando descuento de stock por pago confirmado order_id={} order_number={}", order.getId(), order.getOrderNumber());

        for (OrderItem item : order.getItems()) {
            if (item.getVariation() != null) {
                decrementVariation(order, item);
            } else {
                decrementSimpleProduct(order, item);
            }
        }

        log.info("Descuento de stock completado order_id={}", order.getId());
    }

    private void decrementVariation(Order order, OrderItem item) {
        ProductVariation variation = variationRepository.findById(item.getVariation().getId()).orElse(null);
        if (variation == null) {
            log.error("Variación no encontrada al descontar stock variation_id={} order_id={}", item.getVariation().getId(), order.getId());
            return;
        }
        if (variation.getStock() < item.getQuantity()) {
            log.error("Stock insuficiente en variación variation_id={} sku={} stock={} solicitado={}",
                    variation.getId(), variation.getSku(), variation.getStock(), item.getQuantity());
            return;
        }

        variation.setStock(variation.getStock() - item.getQuantity());
        variationRepository.save(variation);
        productVariationService.recalculateParentAggregate(variation.getProduct().getId());

        log.info("Stock descontado de variación (pago confirmado) variation_id={} sku={} cantidad={} stock_nuevo={}",
                variation.getId(), variation.getSku(), item.getQuantity(), variation.getStock());
    }

    private void decrementSimpleProduct(Order order, OrderItem item) {
        Product product = productRepository.findById(item.getProduct().getId()).orElse(null);
        if (product == null) {
            log.error("Producto no encontrado al descontar stock product_id={} order_id={}", item.getProduct().getId(), order.getId());
            return;
        }
        if (!product.isManageStock()) {
            return;
        }
        if (product.getStock() < item.getQuantity()) {
            log.error("Stock insuficiente en producto product_id={} sku={} stock={} solicitado={}",
                    product.getId(), product.getSku(), product.getStock(), item.getQuantity());
            return;
        }

        product.setStock(product.getStock() - item.getQuantity());
        productRepository.save(product);

        log.info("Stock descontado de producto simple (pago confirmado) product_id={} sku={} cantidad={} stock_nuevo={}",
                product.getId(), product.getSku(), item.getQuantity(), product.getStock());
    }
}
