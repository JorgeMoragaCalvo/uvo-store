package org.uvo.uvostore.service.pos;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    // En mayúsculas y no `log` como en el resto del proyecto: aquí `log` es el SyncWebhookLog que se pasa
    // por todos los métodos, y tener las dos cosas con el mismo nombre se presta a escribir una creyendo
    // que es la otra.
    private static final Logger LOG = LoggerFactory.getLogger(PosWebhookServiceImpl.class);

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

        // F13. El stock se escribe solo si nadie lo ha movido desde que el POS lo leyó.
        //
        // Antes era setStock(absoluto) + save, así que una venta web concurrente desaparecía: POS lee 5,
        // la venta deja 4, llega el absoluto 5 y el stock vuelve a 5 — se puede vender otra vez algo que
        // ya no está. La venta sí usa un UPDATE condicional, así que la carrera la perdía siempre el
        // descuento.
        //
        // Y la autoridad no es una decisión nueva: ya está tomada y escrita en
        // PosOrderNotifier.checkStockDivergence —"el descuento propio es la autoridad sobre el stock web y
        // lo que responde el POS es una observación: si no coincide, se registra la divergencia en vez de
        // pisarla"—. Esta punta hacía justo lo contrario. Aquí se alinea con la otra.
        Product product = mapping.get().getProduct();
        int updated = productRepository.setStockIfUnchanged(product.getId(), payload.oldStock(), payload.stockWeb());
        if (updated == 0) {
            // Cero filas es el único dato de fiar: alguien movió el stock entre la lectura del POS y este
            // evento —una venta, o este mismo evento reenviado fuera de orden—. No se pisa: se informa.
            return markDivergent(log, product, payload);
        }
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

    /**
     * F13. El evento llegó con una foto del stock que ya no es la nuestra, así que no se aplica.
     *
     * <p>No es un {@code SUCCESS}: decir que se aplicó algo que no se aplicó es peor que no decir nada.
     * Y no es exactamente un fallo del POS tampoco — es una divergencia entre las dos puntas, la misma
     * que {@code PosOrderNotifier.checkStockDivergence} ya registra en el sentido contrario. Se informa
     * con las mismas palabras para que las dos mitades se puedan leer juntas.
     */
    private SyncWebhookLog markDivergent(SyncWebhookLog log, Product product, StockUpdatePayload payload) {
        // Se vuelve a leer: el @Modifying limpia el contexto de persistencia, así que la instancia en
        // memoria ya no dice nada fiable sobre el stock actual — y ese número es justamente el dato del
        // mensaje.
        int currentStock = productRepository.findById(product.getId()).map(Product::getStock).orElse(-1);
        String message = ("Divergencia de stock con el POS: producto %d tiene %d en la tienda y el POS declaró"
                + " haber visto %d antes de dejarlo en %d. No se sobrescribe.")
                .formatted(product.getId(), currentStock, payload.oldStock(), payload.stockWeb());
        LOG.warn(message);
        Sentry.captureMessage(message);
        return markFailed(log, message);
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
