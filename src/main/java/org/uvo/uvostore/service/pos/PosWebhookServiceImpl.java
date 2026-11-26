package org.uvo.uvostore.service.pos;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.repository.PosConnectionRepository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Los avisos del POS (contrato §5). IMP-08: cada uno habla de un Producto del POS ({@link PosProductResolver}: un
 * producto simple o una variante de la tienda), trae su {@code version} y pasa por el mismo camino que la carga y la
 * conciliación ({@link PosCatalogWriter}). Un aviso con una versión ya aplicada o más vieja se registra como
 * descartado y el controlador responde {@code ignored: true}.
 */
@Service
public class PosWebhookServiceImpl implements PosWebhookService {

    private final PosConnectionRepository posConnectionRepository;
    private final PosProductResolver resolver;
    private final PosCatalogWriter catalogWriter;
    private final PosInboundLog inboundLog;

    public PosWebhookServiceImpl(PosConnectionRepository posConnectionRepository, PosProductResolver resolver,
                                 PosCatalogWriter catalogWriter, PosInboundLog inboundLog) {
        this.posConnectionRepository = posConnectionRepository;
        this.resolver = resolver;
        this.catalogWriter = catalogWriter;
        this.inboundLog = inboundLog;
    }

    @Override
    @Transactional
    public SyncWebhookLog handleStockUpdated(StockUpdatePayload payload) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("product_id", payload.productId());
        raw.put("sku", payload.sku());
        raw.put("version", payload.version());
        raw.put("old_stock", payload.oldStock());
        raw.put("new_stock", payload.newStock());
        raw.put("stock_web", payload.stockWeb());
        SyncWebhookLog log = inboundLog.open(payload.companyId(), "stock.updated", raw);

        Optional<PosConnection> connection = posConnectionRepository.findByCompanyId(payload.companyId())
                .filter(PosConnection::isActive);
        if (connection.isEmpty()) {
            return inboundLog.failed(log, "Conexión POS no encontrada");
        }
        Optional<PosTarget> target = resolver.resolve(payload.companyId(), payload.productId())
                .filter(found -> found.mapping().getSyncStatus() == SyncStatus.ACTIVE && found.mapping().isSyncStock());
        if (target.isEmpty()) {
            return inboundLog.failed(log, "El Producto %d del POS no está vinculado en la tienda, o no sincroniza stock."
                    .formatted(payload.productId()));
        }
        return close(log, catalogWriter.apply(target.get(), payload.version(),
                new PosCatalogFields(null, null, null, payload.stockWeb())));
    }

    @Override
    @Transactional
    public SyncWebhookLog handleProductUpdated(ProductUpdatePayload payload) {
        SyncWebhookLog log = inboundLog.open(payload.companyId(), "product.updated", Map.of(
                "product_id", payload.productId(), "sku", payload.sku(), "version", payload.version(),
                "data", payload.data()));

        Optional<PosTarget> target = resolver.resolve(payload.companyId(), payload.productId())
                .filter(found -> found.mapping().getSyncStatus() == SyncStatus.ACTIVE);
        if (target.isEmpty()) {
            return inboundLog.failed(log, "El Producto %d del POS no está vinculado en la tienda."
                    .formatted(payload.productId()));
        }
        // Solo lo que vino (el ejemplo del contrato trae solo lo cambiado); stock_web también, si vino.
        Map<String, Object> data = payload.data();
        return close(log, catalogWriter.apply(target.get(), payload.version(), fieldsOf(data)));
    }

    @Override
    @Transactional
    public SyncWebhookLog handleStockAlert(StockAlertPayload payload) {
        SyncWebhookLog log = inboundLog.open(payload.companyId(), "stock.alert", Map.of(
                "product_id", payload.productId(), "sku", payload.sku(),
                "current_stock", payload.currentStock(), "current_stock_web", payload.currentStockWeb(),
                "alert_type", payload.alertType()));
        // Informativo (§5): no cambia nada.
        return inboundLog.success(log);
    }

    /**
     * Un Producto nuevo en el POS. Si ya está vinculado (por ejemplo, la variante se vinculó antes de que llegara el
     * aviso), se aplica como un cambio; si no, solo se registra: la carga la hace {@code /api/sync/product}, y una
     * variante se vincula desde el panel.
     */
    @Override
    @Transactional
    public SyncWebhookLog handleProductCreated(ProductCreatedPayload payload) {
        SyncWebhookLog log = inboundLog.open(payload.companyId(), "product.created", Map.of(
                "product_id", payload.productId(), "sku", payload.sku(), "version", payload.version(),
                "data", payload.data()));
        Optional<PosTarget> target = resolver.resolve(payload.companyId(), payload.productId())
                .filter(found -> found.mapping().getSyncStatus() == SyncStatus.ACTIVE);
        if (target.isEmpty()) {
            return inboundLog.success(log, "Producto nuevo del POS sin vincular: se registra y no se crea nada.");
        }
        return close(log, catalogWriter.apply(target.get(), payload.version(), fieldsOf(payload.data())));
    }

    private SyncWebhookLog close(SyncWebhookLog log, PosCatalogWriter.Result result) {
        return switch (result.outcome()) {
            case APPLIED -> inboundLog.success(log, result.message());
            case ALREADY_APPLIED, STALE -> inboundLog.discarded(log, result.message());
            case INACTIVE, MISMATCH -> inboundLog.failed(log, result.message());
        };
    }

    /** Los campos del POS en un {@code data}: en snake_case, como el contrato, o en camelCase. */
    static PosCatalogFields fieldsOf(Map<String, Object> data) {
        if (data == null) {
            return new PosCatalogFields(null, null, null, null);
        }
        Object exempt = data.containsKey("tax_exempt") ? data.get("tax_exempt") : data.get("taxExempt");
        Object stockWeb = data.containsKey("stock_web") ? data.get("stock_web") : data.get("stockWeb");
        return new PosCatalogFields(text(data.get("name")), price(data.get("price")),
                exempt == null ? null : Boolean.valueOf(exempt.toString()),
                stockWeb == null ? null : (int) Double.parseDouble(stockWeb.toString()));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static BigDecimal price(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }
}
