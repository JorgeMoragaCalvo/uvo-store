package org.uvo.uvostore.service.pos;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.entity.pos.enums.WebhookStatus;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.SyncWebHookLogRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class PosWebhookServiceImpl implements PosWebhookService {

    private final SyncWebHookLogRepository webhookLogRepository;
    private final PosConnectionRepository posConnectionRepository;
    private final ProductSyncMappingRepository mappingRepository;
    private final ProductRepository productRepository;

    public PosWebhookServiceImpl(SyncWebHookLogRepository webhookLogRepository, PosConnectionRepository posConnectionRepository,
                                  ProductSyncMappingRepository mappingRepository, ProductRepository productRepository) {
        this.webhookLogRepository = webhookLogRepository;
        this.posConnectionRepository = posConnectionRepository;
        this.mappingRepository = mappingRepository;
        this.productRepository = productRepository;
    }

    @Override
    @Transactional
    public SyncWebhookLog handleStockUpdated(StockUpdatePayload payload) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("product_id", payload.productId());
        raw.put("sku", payload.sku());
        raw.put("old_stock", payload.oldStock());
        raw.put("new_stock", payload.newStock());
        raw.put("stock_web", payload.stockWeb());
        SyncWebhookLog log = logWebhook(payload.companyId(), "stock.updated", raw);

        Optional<PosConnection> connection = posConnectionRepository.findByCompanyId(payload.companyId()).filter(PosConnection::isActive);
        if (connection.isEmpty()) {
            return markFailed(log, "Conexión POS no encontrada");
        }

        Optional<ProductSyncMapping> mapping = mappingRepository.findByExternalIdAndCompanyId(payload.productId(), payload.companyId())
                .filter(m -> m.getSyncStatus() == SyncStatus.ACTIVE && m.isSyncStock());
        if (mapping.isEmpty()) {
            return markFailed(log, "No se pudo actualizar el stock");
        }

        Product product = mapping.get().getProduct();
        product.setStock(payload.stockWeb());
        productRepository.save(product);
        markSynced(mapping.get());

        return markSuccess(log);
    }

    @Override
    @Transactional
    public SyncWebhookLog handleProductUpdated(ProductUpdatePayload payload) {
        SyncWebhookLog log = logWebhook(payload.companyId(), "product.updated", Map.of(
                "product_id", payload.productId(), "sku", payload.sku(), "data", payload.data()));

        Optional<ProductSyncMapping> mapping = mappingRepository.findByExternalIdAndCompanyId(payload.productId(), payload.companyId())
                .filter(m -> m.getSyncStatus() == SyncStatus.ACTIVE);
        if (mapping.isEmpty()) {
            return markFailed(log, "No se pudo actualizar el producto");
        }

        ProductSyncMapping m = mapping.get();
        Product product = m.getProduct();
        Map<String, Object> data = payload.data();
        boolean changed = false;

        if (m.isSyncPrice() && data.get("price") != null) {
            product.setPrice(new java.math.BigDecimal(data.get("price").toString()));
            changed = true;
        }
        if (m.isSyncName() && data.get("name") != null) {
            product.setName(data.get("name").toString());
            product.setSlug(slugify(data.get("name").toString()));
            changed = true;
        }
        if (m.isSyncDescription() && data.get("description") != null) {
            product.setDescription(data.get("description").toString());
            changed = true;
        }

        if (changed) {
            productRepository.save(product);
            markSynced(m);
        }

        return markSuccess(log);
    }

    @Override
    @Transactional
    public SyncWebhookLog handleStockAlert(StockAlertPayload payload) {
        SyncWebhookLog log = logWebhook(payload.companyId(), "stock.alert", Map.of(
                "product_id", payload.productId(), "sku", payload.sku(),
                "current_stock", payload.currentStock(), "current_stock_web", payload.currentStockWeb(),
                "alert_type", payload.alertType()));
        // "out_of_stock" alerts would notify an admin in the source app — no notification
        // channel is wired up yet, so this just logs the event (TODO in the Laravel source too).
        return markSuccess(log);
    }

    @Override
    @Transactional
    public SyncWebhookLog handleProductCreated(ProductCreatedPayload payload) {
        SyncWebhookLog log = logWebhook(payload.companyId(), "product.created", Map.of(
                "product_id", payload.productId(), "sku", payload.sku(), "data", payload.data()));
        // Auto-creation from this event is a TODO in the Laravel source too — only logged.
        return markSuccess(log);
    }

    private SyncWebhookLog logWebhook(Long companyId, String eventType, Map<String, Object> payload) {
        SyncWebhookLog log = new SyncWebhookLog();
        log.setCompanyId(companyId);
        log.setEventType(eventType);
        log.setStatus(WebhookStatus.RECEIVED);
        log.setPayload(payload);
        return webhookLogRepository.save(log);
    }

    private SyncWebhookLog markSuccess(SyncWebhookLog log) {
        log.setStatus(WebhookStatus.SUCCESS);
        log.setProcessedAt(Instant.now());
        return webhookLogRepository.save(log);
    }

    private SyncWebhookLog markFailed(SyncWebhookLog log, String error) {
        log.setStatus(WebhookStatus.FAILED);
        log.setErrorMessage(error);
        log.setProcessedAt(Instant.now());
        return webhookLogRepository.save(log);
    }

    private void markSynced(ProductSyncMapping mapping) {
        mapping.setSyncStatus(SyncStatus.ACTIVE);
        mapping.setSyncError(null);
        mapping.setLastSyncedAt(Instant.now());
        mappingRepository.save(mapping);
    }

    private static String slugify(String value) {
        return value.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
