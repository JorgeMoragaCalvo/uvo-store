package org.uvo.uvostore.service.order.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.uvo.uvostore.entity.order.Order;
import org.uvo.uvostore.entity.order.OrderItem;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.service.pos.PosClient;
import org.uvo.uvostore.service.pos.PosOrderItem;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

// Ports NotifyPosOnOrderCompleted + the NotifyOrderToPOS job it dispatches (run inline, AFTER_COMMIT,
// instead of queued — same "no job queue infrastructure yet" gap already flagged for the POS
// webhook handlers in Fase 5).
@Component
public class PosNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(PosNotificationListener.class);

    private final OrderRepository orderRepository;
    private final ProductSyncMappingRepository mappingRepository;
    private final PosConnectionRepository posConnectionRepository;
    private final PosClient posClient;

    public PosNotificationListener(OrderRepository orderRepository, ProductSyncMappingRepository mappingRepository,
                                    PosConnectionRepository posConnectionRepository, PosClient posClient) {
        this.orderRepository = orderRepository;
        this.mappingRepository = mappingRepository;
        this.posConnectionRepository = posConnectionRepository;
        this.posClient = posClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onOrderCompleted(OrderCompletedEvent event) {
        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new NoSuchElementException("Order " + event.orderId() + " not found"));

        Set<Long> companyIds = order.getItems().stream()
                .map(item -> mappingRepository.findByProductId(item.getProduct().getId()))
                .flatMap(java.util.Optional::stream)
                .map(ProductSyncMapping::getCompanyId)
                .collect(Collectors.toSet());

        if (companyIds.isEmpty()) {
            log.info("Orden sin productos sincronizados, no se notifica a POS order_id={}", order.getId());
            return;
        }

        for (Long companyId : companyIds) {
            notifyOneCompany(order, companyId);
        }
    }

    private void notifyOneCompany(Order order, Long companyId) {
        try {
            PosConnection connection = posConnectionRepository.findByCompanyId(companyId)
                    .filter(PosConnection::isActive)
                    .orElse(null);
            if (connection == null) {
                log.warn("Conexión POS no encontrada, orden no notificada order_id={} company_id={}", order.getId(), companyId);
                return;
            }

            List<PosOrderItem> items = order.getItems().stream()
                    .map(item -> mappingRepository.findByProductIdAndCompanyId(item.getProduct().getId(), companyId)
                            .map(mapping -> new PosOrderItem(mapping.getExternalId(), item.getQuantity(), item.getPrice())))
                    .flatMap(java.util.Optional::stream)
                    .toList();

            if (items.isEmpty()) {
                log.info("No hay productos sincronizados en esta orden para company_id={} order_id={}", companyId, order.getId());
                return;
            }

            Map<String, Object> response = posClient.notifyOrder(connection, order.getOrderNumber(), items);
            log.info("Orden notificada a POS exitosamente order_id={} order_number={} company_id={} response={}",
                    order.getId(), order.getOrderNumber(), companyId, response);

            applyStockResults(response, companyId);
        } catch (Exception e) {
            log.error("Error notificando orden a POS order_id={} company_id={} error={}", order.getId(), companyId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void applyStockResults(Map<String, Object> response, Long companyId) {
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return;
        }
        Object results = dataMap.get("results");
        if (!(results instanceof List<?> resultList)) {
            return;
        }

        for (Object entry : resultList) {
            if (!(entry instanceof Map<?, ?> result)) {
                continue;
            }
            boolean success = Boolean.TRUE.equals(result.get("success"));
            if (!success) {
                continue;
            }
            Object productId = result.get("product_id");
            Object newStockWeb = result.get("new_stock_web");
            if (productId == null || newStockWeb == null) {
                continue;
            }

            Long externalId = Long.valueOf(productId.toString());
            int newStock = (int) Double.parseDouble(newStockWeb.toString());

            mappingRepository.findByExternalIdAndCompanyId(externalId, companyId).ifPresent(mapping -> {
                mapping.getProduct().setStock(newStock);
            });
        }
    }
}
