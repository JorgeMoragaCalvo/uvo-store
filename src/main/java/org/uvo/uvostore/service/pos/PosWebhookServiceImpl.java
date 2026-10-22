package org.uvo.uvostore.service.pos;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.ProductVariationSyncMapping;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.ProductVariationSyncMappingRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PosWebhookServiceImpl implements PosWebhookService {

    // En mayúsculas y no `log` como en el resto del proyecto: aquí `log` es el SyncWebhookLog que se pasa
    // por todos los métodos, y tener las dos cosas con el mismo nombre se presta a escribir una creyendo
    // que es la otra.
    private static final Logger LOG = LoggerFactory.getLogger(PosWebhookServiceImpl.class);

    private final PosConnectionRepository posConnectionRepository;
    private final ProductSyncMappingRepository mappingRepository;
    private final ProductVariationSyncMappingRepository variantMappingRepository;
    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final PosCatalogWriter catalogWriter;
    private final PosInboundLog inboundLog;

    public PosWebhookServiceImpl(PosConnectionRepository posConnectionRepository,
                                  ProductSyncMappingRepository mappingRepository,
                                  ProductVariationSyncMappingRepository variantMappingRepository,
                                  ProductRepository productRepository, ProductVariationRepository variationRepository,
                                  PosCatalogWriter catalogWriter, PosInboundLog inboundLog) {
        this.posConnectionRepository = posConnectionRepository;
        this.mappingRepository = mappingRepository;
        this.variantMappingRepository = variantMappingRepository;
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.catalogWriter = catalogWriter;
        this.inboundLog = inboundLog;
    }

    @Override
    @Transactional
    public SyncWebhookLog handleStockUpdated(StockUpdatePayload payload) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("product_id", payload.productId());
        raw.put("variant_id", payload.variantId());
        raw.put("sku", payload.sku());
        raw.put("old_stock", payload.oldStock());
        raw.put("new_stock", payload.newStock());
        raw.put("stock_web", payload.stockWeb());
        SyncWebhookLog log = inboundLog.open(payload.companyId(), "stock.updated", raw);

        Optional<PosConnection> connection = posConnectionRepository.findByCompanyId(payload.companyId()).filter(PosConnection::isActive);
        if (connection.isEmpty()) {
            return inboundLog.failed(log, "Conexión POS no encontrada");
        }

        Optional<ProductSyncMapping> mapping = mappingRepository.findByExternalIdAndCompanyId(payload.productId(), payload.companyId())
                .filter(m -> m.getSyncStatus() == SyncStatus.ACTIVE && m.isSyncStock());
        if (mapping.isEmpty()) {
            return inboundLog.failed(log, "No se pudo actualizar el stock");
        }

        // POS-5. El stock de un producto variable es la suma de sus variantes: se escribe en una variante, nunca en
        // el padre. Sin esta guarda, un evento sin variantId pisaría la suma, y la siguiente venta de cualquier talla
        // (recalculateStockFromVariations) la volvería a dejar como estaba.
        Product product = mapping.get().getProduct();
        boolean variable = product.getProductType() == ProductType.VARIABLE;
        if (variable && payload.variantId() == null) {
            return inboundLog.failed(log, ("El producto %d tiene variantes en la tienda: el evento de stock tiene que "
                    + "traer variantId.").formatted(product.getId()));
        }
        if (!variable && payload.variantId() != null) {
            return inboundLog.failed(log, ("El producto %d es simple en la tienda y el evento de stock trae una "
                    + "variante: cambiar el tipo requiere revisión manual.").formatted(product.getId()));
        }
        if (variable) {
            return updateVariantStock(log, mapping.get(), payload);
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
        Long productId = mapping.get().getProduct().getId();
        int updated = productRepository.setStockIfUnchanged(productId, payload.oldStock(), payload.stockWeb());
        if (updated == 0) {
            // Cero filas es el único dato de fiar: alguien movió el stock entre la lectura del POS y este
            // evento —una venta, o este mismo evento reenviado fuera de orden—. No se pisa: se informa.
            //
            // Se vuelve a leer: el @Modifying limpia el contexto de persistencia, así que la instancia en
            // memoria ya no dice nada fiable sobre el stock actual.
            int currentStock = productRepository.findById(productId).map(Product::getStock).orElse(-1);
            if (currentStock == payload.stockWeb()) {
                // POS-3. El stock ya es el que pide el evento: llegó repetido (el POS lo reintentó, con otra firma)
                // o ya estaba reflejado. No hay nada que corregir, y avisarlo como divergencia era una alerta
                // falsa a Sentry por cada reintento.
                return inboundLog.discarded(log, "El stock ya es %d: el evento llegó repetido o ya estaba reflejado."
                        .formatted(currentStock));
            }
            return markDivergent(log, productId, currentStock, payload);
        }
        // POS-3. Una actualización dirigida y no save(mapping): tras el clear de arriba el mapeo está
        // desconectado, y el merge reescribiría todas sus columnas, la versión de catálogo incluida, con lo que
        // se leyó antes de que otra entrada confirmara (ver ProductSyncMappingRepository.touchSynced).
        mappingRepository.touchSynced(mapping.get().getId(), Instant.now());

        return inboundLog.success(log);
    }

    /**
     * POS-5. Lo mismo que el stock de un producto simple (F13), en una variante: UPDATE condicionado al
     * {@code oldStock}, y si no encaja, descarte o divergencia.
     *
     * <p>El padre se bloquea primero. Es el orden de todo lo que toca variaciones (la venta incluida): con el
     * contrario, esta escritura y una actualización de catálogo del mismo producto podrían esperarse mutuamente.
     * Después de la variante, el stock del padre vuelve a ser la suma de sus variantes.
     */
    private SyncWebhookLog updateVariantStock(SyncWebhookLog log, ProductSyncMapping mapping, StockUpdatePayload payload) {
        Long productId = mapping.getProduct().getId();
        Optional<ProductVariationSyncMapping> variant = variantMappingRepository
                .findByMappingIdAndExternalVariantId(mapping.getId(), payload.variantId());
        if (variant.isEmpty()) {
            return inboundLog.failed(log, "La variante %d del producto %d no está mapeada en la tienda."
                    .formatted(payload.variantId(), productId));
        }
        Long variationId = variant.get().getVariation().getId();
        Long mappingId = mapping.getId();

        productRepository.findByIdForUpdate(productId);
        int updated = variationRepository.setStockIfUnchanged(variationId, payload.oldStock(), payload.stockWeb());
        if (updated == 0) {
            int currentStock = variationRepository.findById(variationId).map(ProductVariation::getStock).orElse(-1);
            if (currentStock == payload.stockWeb()) {
                return inboundLog.discarded(log, "El stock ya es %d: el evento llegó repetido o ya estaba reflejado."
                        .formatted(currentStock));
            }
            String message = ("Divergencia de stock con el POS: la variación %d del producto %d tiene %d en la tienda "
                    + "y el POS declaró haber visto %d antes de dejarla en %d. No se sobrescribe.")
                    .formatted(variationId, productId, currentStock, payload.oldStock(), payload.stockWeb());
            LOG.warn(message);
            Sentry.captureMessage(message);
            return inboundLog.failed(log, message);
        }
        productRepository.recalculateStockFromVariations(productId);
        mappingRepository.touchSynced(mappingId, Instant.now());
        return inboundLog.success(log);
    }

    @Override
    @Transactional
    public SyncWebhookLog handleProductUpdated(ProductUpdatePayload payload) {
        SyncWebhookLog log = inboundLog.open(payload.companyId(), "product.updated", Map.of(
                "product_id", payload.productId(), "sku", payload.sku(), "version", payload.version(),
                "data", payload.data()));

        Optional<ProductSyncMapping> mapping = mappingRepository.findByExternalIdAndCompanyId(payload.productId(), payload.companyId())
                .filter(m -> m.getSyncStatus() == SyncStatus.ACTIVE);
        if (mapping.isEmpty()) {
            return inboundLog.failed(log, "No se pudo actualizar el producto");
        }

        // POS-3. Por el mismo camino que la sincronización: flags del mapeo, versión y cerrojo. Antes aplicaba
        // lo último que llegaba aunque fuera más viejo que lo ya aplicado, y reescribía el producto entero con
        // el stock que leyó al cargarlo.
        Map<String, Object> data = payload.data();
        // POS-5. Las variantes, con la misma forma que en la sincronización y las mismas reglas.
        List<PosVariantFields> variants = PosVariants.fromJson(data.get("variants"), "variantId", "stock");
        if (variants != null) {
            String invalid = PosVariants.validate(variants);
            if (invalid != null) {
                return inboundLog.failed(log, invalid);
            }
        }
        PosCatalogWriter.Result result = catalogWriter.apply(mapping.get(), payload.version(), new PosCatalogFields(
                text(data.get("name")), text(data.get("description")), price(data.get("price")), variants));

        return switch (result.outcome()) {
            case APPLIED -> inboundLog.success(log);
            case ALREADY_APPLIED, STALE -> inboundLog.discarded(log, result.message());
            case INACTIVE, MISMATCH -> inboundLog.failed(log, result.message());
        };
    }

    @Override
    @Transactional
    public SyncWebhookLog handleStockAlert(StockAlertPayload payload) {
        SyncWebhookLog log = inboundLog.open(payload.companyId(), "stock.alert", Map.of(
                "product_id", payload.productId(), "sku", payload.sku(),
                "current_stock", payload.currentStock(), "current_stock_web", payload.currentStockWeb(),
                "alert_type", payload.alertType()));
        // "out_of_stock" alerts would notify an admin in the source app — no notification
        // channel is wired up yet, so this just logs the event (TODO in the Laravel source too).
        return inboundLog.success(log);
    }

    @Override
    @Transactional
    public SyncWebhookLog handleProductCreated(ProductCreatedPayload payload) {
        SyncWebhookLog log = inboundLog.open(payload.companyId(), "product.created", Map.of(
                "product_id", payload.productId(), "sku", payload.sku(), "data", payload.data()));
        // Auto-creation from this event is a TODO in the Laravel source too — only logged.
        return inboundLog.success(log);
    }

    /**
     * F13. El evento llegó con una foto del stock que ya no es la nuestra, así que no se aplica.
     *
     * <p>No es un {@code SUCCESS}: decir que se aplicó algo que no se aplicó es peor que no decir nada.
     * Y no es exactamente un fallo del POS tampoco — es una divergencia entre las dos puntas, la misma
     * que {@code PosOrderNotifier.checkStockDivergence} ya registra en el sentido contrario. Se informa
     * con las mismas palabras para que las dos mitades se puedan leer juntas.
     */
    private SyncWebhookLog markDivergent(SyncWebhookLog log, Long productId, int currentStock, StockUpdatePayload payload) {
        String message = ("Divergencia de stock con el POS: producto %d tiene %d en la tienda y el POS declaró"
                + " haber visto %d antes de dejarlo en %d. No se sobrescribe.")
                .formatted(productId, currentStock, payload.oldStock(), payload.stockWeb());
        LOG.warn(message);
        Sentry.captureMessage(message);
        return inboundLog.failed(log, message);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static BigDecimal price(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }
}
