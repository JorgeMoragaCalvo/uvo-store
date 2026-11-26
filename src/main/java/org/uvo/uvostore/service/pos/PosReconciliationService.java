package org.uvo.uvostore.service.pos;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.ProductVariationSyncMapping;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;
import org.uvo.uvostore.repository.ProductVariationSyncMappingRepository;
import org.uvo.uvostore.service.BusinessException;
import org.uvo.uvostore.service.metrics.BusinessMetrics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * POS-4. Compara el stock y el catálogo de los productos mapeados con lo que el POS dice tener, y corrige hacia
 * el POS.
 *
 * <p>Hasta ahora una divergencia solo se veía al notificar una orden ({@code PosOrderNotifier.checkStockDivergence})
 * o al llegar un webhook. Si un evento se perdía, la diferencia duraba para siempre. Esto la busca cada
 * {@code app.pos.reconciliation.interval-ms} ({@code PosReconciliationJob}) y cuando el administrador pide una
 * resincronización desde el panel.
 *
 * <p><b>Lo que no se puede deshacer es una venta web en vuelo</b>: una que nuestro stock ya descontó y que el POS
 * todavía no tiene. Igualar ese producto al POS devolvería unidades vendidas. Por eso, por cada lote:
 * <ol>
 *   <li>Se toma una foto: el stock de cada producto como <b>valor</b>, y qué productos tienen una venta en vuelo
 *       (una fila {@code POS_ORDER} sin entregar, o una orden cobrada con el stock aún sin descontar).</li>
 *   <li>Se lee el POS, fuera de toda transacción.</li>
 *   <li>Se corrige solo lo que no tenía ventas en vuelo en la foto, con el UPDATE condicionado al valor de la foto
 *       ({@code setStockIfUnchanged}, el de F13). Una venta que empezó después de la foto ya movió el stock, el
 *       UPDATE no encaja y el producto queda para la próxima pasada. Una venta entregada al POS antes de la foto
 *       ya estaba en el POS cuando se leyó.</li>
 * </ol>
 *
 * <p>El catálogo (precio, nombre, descripción) va por {@link PosCatalogWriter#reconcile}: los mismos flags y el
 * mismo cerrojo que los eventos, y una lectura más vieja que un evento ya aplicado no lo pisa.
 *
 * <p>Dos conciliaciones a la vez (el job y el botón) no se estorban: la segunda no encaja en el UPDATE
 * condicional, y el catálogo se ordena por el cerrojo del producto.
 *
 * <p><b>Variantes</b> (POS-5): en un producto variable el stock se compara variante por variante y nunca en el
 * padre, que es la suma de sus variantes. La corrección bloquea antes el padre, como todo lo que escribe variaciones.
 * La venta en vuelo sigue contando por producto: retrasa una pasada las otras tallas de un producto con una venta
 * pendiente, que es lo prudente. Los conteos también son por producto.
 */
@Service
public class PosReconciliationService {

    private static final Logger LOG = LoggerFactory.getLogger(PosReconciliationService.class);

    /** Cuántos ids de cada tipo se guardan en el registro: el conteo va siempre entero. */
    static final int MAX_IDS_IN_LOG = 50;

    /** IMP-08. El máximo de Productos por lectura del snapshot (contrato §5). */
    static final int MAX_IDS_PER_READ = 200;

    public enum Trigger {
        SCHEDULED, MANUAL
    }

    private final PosConnectionRepository connectionRepository;
    private final ProductSyncMappingRepository mappingRepository;
    private final ProductVariationSyncMappingRepository variantMappingRepository;
    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final OutboxMessageRepository outboxRepository;
    private final OrderRepository orderRepository;
    private final PosClient posClient;
    private final PosCatalogWriter catalogWriter;
    private final PosInboundLog inboundLog;
    private final PosIntegrationPolicy posPolicy;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnlyTransactions;
    private final int batchSize;
    private final BusinessMetrics metrics;

    public PosReconciliationService(PosConnectionRepository connectionRepository,
                                    ProductSyncMappingRepository mappingRepository,
                                    ProductVariationSyncMappingRepository variantMappingRepository,
                                    ProductRepository productRepository, ProductVariationRepository variationRepository,
                                    OutboxMessageRepository outboxRepository,
                                    OrderRepository orderRepository, PosClient posClient,
                                    PosCatalogWriter catalogWriter, PosInboundLog inboundLog,
                                    PosIntegrationPolicy posPolicy, PlatformTransactionManager transactionManager,
                                    @Value("${app.pos.reconciliation.batch-size:200}") int batchSize,
                                    BusinessMetrics metrics) {
        this.metrics = metrics;
        this.connectionRepository = connectionRepository;
        this.mappingRepository = mappingRepository;
        this.variantMappingRepository = variantMappingRepository;
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.outboxRepository = outboxRepository;
        this.orderRepository = orderRepository;
        this.posClient = posClient;
        this.catalogWriter = catalogWriter;
        this.inboundLog = inboundLog;
        this.posPolicy = posPolicy;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnlyTransactions = new TransactionTemplate(transactionManager);
        this.readOnlyTransactions.setReadOnly(true);
        this.batchSize = batchSize;
    }

    /** La pasada programada: todas las conexiones activas de las tiendas con la integración encendida. */
    public PosReconciliationSummary reconcileAll() {
        PosReconciliationSummary total = PosReconciliationSummary.empty();
        for (PosConnection connection : connectionRepository.findAllActive()) {
            if (posPolicy.isEnabledFor(connection.getStore())) {
                total = total.plus(reconcileConnection(connection, Trigger.SCHEDULED));
            }
        }
        return total;
    }

    /** La resincronización del panel: todos los productos mapeados de una tienda, ahora. */
    public PosReconciliationSummary reconcileStore(Store store) {
        if (!posPolicy.isEnabledFor(store)) {
            throw new BusinessException("La sincronización con UvoPOS está apagada para esta tienda: actívala y "
                    + "guarda los ajustes antes de resincronizar.");
        }
        List<PosConnection> connections = connectionRepository.findActiveByStoreId(store.getId());
        if (connections.isEmpty()) {
            throw new BusinessException("Esta tienda no tiene una conexión activa con UvoPOS: configura sus dos "
                    + "llaves antes de resincronizar.");
        }
        PosReconciliationSummary total = PosReconciliationSummary.empty();
        for (PosConnection connection : connections) {
            total = total.plus(reconcileConnection(connection, Trigger.MANUAL));
        }
        return total;
    }

    private PosReconciliationSummary reconcileConnection(PosConnection connection, Trigger trigger) {
        Tally tally = new Tally();
        String failure = null;
        long afterId = 0;
        batches:
        while (true) {
            long cursor = afterId;
            Batch batch = readOnlyTransactions.execute(status -> snapshot(connection.getCompanyId(), cursor));
            if (batch == null || batch.mappingCount() == 0) {
                break;
            }

            // IMP-08. Hasta 200 Productos del POS por lectura (§5): un lote de mapeos con variantes puede tener más.
            List<Item> items = batch.items();
            for (int from = 0; from < items.size(); from += MAX_IDS_PER_READ) {
                List<Item> chunk = items.subList(from, Math.min(from + MAX_IDS_PER_READ, items.size()));
                List<Long> ids = chunk.stream().map(Item::externalId).toList();
                PosCatalogSnapshot pos = posClient.readCatalog(connection, ids);
                if (pos.isFailed()) {
                    // Sin la lectura no hay con qué comparar. Se corta esta conexión; las demás siguen.
                    failure = pos.failure();
                    break batches;
                }
                Set<Long> unaccounted = pos.unaccounted(ids);
                for (Item item : chunk) {
                    if (unaccounted.contains(item.externalId())) {
                        // §5: un id que no está ni en data ni en missing es un lote incompleto, no un producto que el
                        // POS no tiene. Se deja para la próxima pasada.
                        tally.error(item, "el POS no lo devolvió ni en data ni en missing");
                        continue;
                    }
                    reconcileItem(item, pos.rows().get(item.externalId()), batch.inFlight(), tally);
                }
            }
            afterId = batch.lastMappingId();
            if (batch.mappingCount() < batchSize) {
                break;
            }
        }

        record(connection, trigger, tally, failure);
        alert(connection, tally, failure);
        countDivergences(tally);
        return tally.toSummary(failure);
    }

    /**
     * PROD-15b. Lo mismo que el aviso, como contador y sin la tienda (ver {@link BusinessMetrics}). Una venta en
     * vuelo no cuenta, por lo mismo que no avisa.
     */
    private void countDivergences(Tally tally) {
        metrics.stockDivergences(BusinessMetrics.DivergenceSource.RECONCILIATION_STOCK, tally.stockCorrected.size());
        metrics.stockDivergences(BusinessMetrics.DivergenceSource.RECONCILIATION_CATALOG, tally.catalogCorrected.size());
        metrics.stockDivergences(BusinessMetrics.DivergenceSource.RECONCILIATION_MISSING, tally.missingInPos.size());
    }

    /**
     * La foto del lote: valores, no entidades, porque las correcciones limpian el contexto de persistencia. IMP-08: un
     * item por Producto del POS: el producto simple, o cada variante vinculada de un producto con variantes.
     */
    private Batch snapshot(Long companyId, long afterId) {
        List<ProductSyncMapping> mappings = mappingRepository.findActiveBatch(companyId, afterId,
                PageRequest.of(0, batchSize));
        if (mappings.isEmpty()) {
            return new Batch(List.of(), Set.of(), 0, afterId);
        }
        Map<Long, List<ProductVariationSyncMapping>> variants = new HashMap<>();
        for (ProductVariationSyncMapping variant : variantMappingRepository.findByMappingIdIn(
                mappings.stream().map(ProductSyncMapping::getId).toList())) {
            variants.computeIfAbsent(variant.getMapping().getId(), id -> new ArrayList<>()).add(variant);
        }
        List<Item> items = new ArrayList<>();
        for (ProductSyncMapping mapping : mappings) {
            if (mapping.getProduct().getProductType() == ProductType.VARIABLE) {
                variants.getOrDefault(mapping.getId(), List.of())
                        .forEach(variant -> items.add(Item.ofVariant(mapping, variant)));
            } else if (mapping.getExternalId() != null) {
                items.add(Item.ofSimple(mapping));
            }
        }
        List<Long> productIds = mappings.stream().map(mapping -> mapping.getProduct().getId()).toList();
        Set<Long> inFlight = new HashSet<>(outboxRepository.findProductsWithUnregisteredSales(companyId, productIds));
        inFlight.addAll(orderRepository.findProductsWithStockPending(productIds));
        return new Batch(items, inFlight, mappings.size(), mappings.getLast().getId());
    }

    private void reconcileItem(Item item, PosCatalogSnapshot.Row row, Set<Long> inFlight, Tally tally) {
        tally.checked++;
        if (row == null) {
            // En missing (§5): el POS no lo tiene, o no es de esta empresa. Hay que revisar el vínculo.
            tally.missingInPos.add(item.productId());
            return;
        }
        // Cada corrección por su lado: un producto que falla no puede llevarse por delante a los demás.
        try {
            reconcileStock(item, row, inFlight, tally);
        } catch (RuntimeException e) {
            tally.fail(item, "stock", e);
        }
        try {
            reconcileCatalog(item, row, tally);
        } catch (RuntimeException e) {
            tally.fail(item, "catálogo", e);
        }
    }

    /**
     * El stock web del POS, con el UPDATE condicionado al valor de la foto. La venta en vuelo es la del producto: en
     * uno con variantes, una talla con una venta pendiente deja todas para la próxima pasada.
     */
    private void reconcileStock(Item item, PosCatalogSnapshot.Row row, Set<Long> inFlight, Tally tally) {
        if (!item.syncStock() || row.stockWeb() == null || row.stockWeb() == item.stock()) {
            return;
        }
        if (inFlight.contains(item.productId())) {
            // La diferencia es, justamente, la venta que el POS todavía no tiene.
            tally.pendingInFlight.add(item.productId());
            return;
        }
        Boolean corrected = transactions.execute(status -> {
            int updated;
            if (item.variationId() == null) {
                updated = productRepository.setStockIfUnchanged(item.productId(), item.stock(), row.stockWeb());
            } else {
                // El padre primero, como la venta: si no, esta corrección y una venta de otra talla del mismo
                // producto podrían esperarse mutuamente.
                productRepository.findByIdForUpdate(item.productId());
                updated = variationRepository.setStockIfUnchanged(item.variationId(), item.stock(), row.stockWeb());
                if (updated == 1) {
                    productRepository.recalculateStockFromVariations(item.productId());
                }
            }
            if (updated == 1) {
                mappingRepository.touchSynced(item.mappingId(), Instant.now());
            }
            return updated == 1;
        });
        if (Boolean.TRUE.equals(corrected)) {
            LOG.warn("Conciliación con el POS: {} tenía stock {} y el POS dice {}; corregido",
                    item.describe(), item.stock(), row.stockWeb());
            tally.stockCorrected.add(item.productId());
        } else {
            tally.changedMeanwhile.add(item.productId());
        }
    }

    private void reconcileCatalog(Item item, PosCatalogSnapshot.Row row, Tally tally) {
        if (row.version() == null) {
            return;
        }
        boolean differs = (item.syncPrice() && row.price() != null
                        && (item.price() == null || row.price().compareTo(item.price()) != 0))
                || (item.variationId() == null && item.syncName() && row.name() != null
                        && !row.name().equals(item.name()))
                || (row.taxExempt() != null && row.taxExempt() != item.taxExempt());
        boolean newer = item.appliedVersion() == null || row.version() > item.appliedVersion();
        if (!differs && !newer) {
            return;
        }

        PosCatalogFields fields = new PosCatalogFields(row.name(), row.price(), row.taxExempt(), null);
        PosCatalogWriter.Result result = transactions.execute(status -> target(item)
                .map(target -> catalogWriter.reconcile(target, row.version(), fields))
                .orElse(null));
        if (result == null) {
            return;
        }
        switch (result.outcome()) {
            case APPLIED -> {
                if (differs) {
                    LOG.warn("Conciliación con el POS: catálogo del producto {} corregido a la versión {}",
                            item.productId(), row.version());
                    tally.catalogCorrected.add(item.productId());
                }
            }
            // La lectura salió antes de que llegara un evento más nuevo, que ya se aplicó: no se pisa.
            case STALE -> tally.staleSnapshots++;
            // Simple aquí y con variantes en el POS: hay que mirarlo, y se avisa en cada pasada hasta que alguien lo haga.
            case MISMATCH -> tally.error(item, result.message());
            // Se pausó mientras tanto, o no puede pasar con reconcile: nada que contar.
            case INACTIVE, ALREADY_APPLIED -> {
            }
        }
    }

    /** El destino del item, cargado dentro de la transacción de la corrección. */
    private java.util.Optional<PosTarget> target(Item item) {
        if (item.variantMappingId() != null) {
            return variantMappingRepository.findById(item.variantMappingId()).map(PosTarget.Variant::new);
        }
        return mappingRepository.findById(item.mappingId()).map(PosTarget.Simple::new);
    }

    private void record(PosConnection connection, Trigger trigger, Tally tally, String failure) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("trigger", trigger.name().toLowerCase(Locale.ROOT));
        payload.put("checked", tally.checked);
        putIds(payload, "stock_corrected", tally.stockCorrected);
        putIds(payload, "catalog_corrected", tally.catalogCorrected);
        putIds(payload, "pending_in_flight", tally.pendingInFlight);
        putIds(payload, "changed_meanwhile", tally.changedMeanwhile);
        putIds(payload, "missing_in_pos", tally.missingInPos);
        payload.put("stale_snapshots", tally.staleSnapshots);
        payload.put("errors", tally.errors);
        SyncWebhookLog log = inboundLog.open(connection.getCompanyId(), "pos.reconciliation", payload);
        if (failure != null) {
            inboundLog.failed(log, failure);
        } else {
            inboundLog.success(log);
        }
    }

    private static void putIds(Map<String, Object> payload, String key, Set<Long> ids) {
        payload.put(key, ids.size());
        payload.put(key + "_ids", ids.stream().limit(MAX_IDS_IN_LOG).toList());
    }

    /**
     * Un solo aviso por conexión y pasada, y solo si hay algo que mirar. Una venta en vuelo no lo es: es
     * transitoria y se resuelve sola cuando el POS la registra.
     */
    private static void alert(PosConnection connection, Tally tally, String failure) {
        String store = connection.getStore() == null ? "?" : connection.getStore().getSlug();
        if (failure != null) {
            String message = "Conciliación con UvoPOS sin hacer (empresa %d, tienda %s): %s"
                    .formatted(connection.getCompanyId(), store, failure);
            LOG.error(message);
            Sentry.captureMessage(message);
            return;
        }
        if (tally.stockCorrected.isEmpty() && tally.catalogCorrected.isEmpty() && tally.missingInPos.isEmpty()
                && tally.errors.isEmpty()) {
            return;
        }
        String message = ("Conciliación con UvoPOS (empresa %d, tienda %s): %d con stock corregido, %d con "
                + "catálogo corregido, %d que el POS no devolvió, %d con error")
                .formatted(connection.getCompanyId(), store, tally.stockCorrected.size(),
                        tally.catalogCorrected.size(), tally.missingInPos.size(), tally.errors.size());
        LOG.warn(message);
        Sentry.captureMessage(message);
    }

    // --- valores de la foto ------------------------------------------------------------------------

    private record Batch(List<Item> items, Set<Long> inFlight, int mappingCount, long lastMappingId) {
    }

    /**
     * IMP-08. Un Producto del POS de la foto, como valores: un producto simple ({@code variationId} nulo) o una
     * variante vinculada. {@code appliedVersion} es la del mapeo del producto o la de la variante.
     */
    private record Item(Long mappingId, Long variantMappingId, Long productId, Long variationId, Long externalId,
                        int stock, BigDecimal price, String name, boolean taxExempt, Long appliedVersion,
                        boolean syncStock, boolean syncPrice, boolean syncName) {

        static Item ofSimple(ProductSyncMapping mapping) {
            var product = mapping.getProduct();
            return new Item(mapping.getId(), null, product.getId(), null, mapping.getExternalId(), product.getStock(),
                    product.getPrice(), product.getName(), product.isTaxExempt(), mapping.getCatalogVersion(),
                    mapping.isSyncStock(), mapping.isSyncPrice(), mapping.isSyncName());
        }

        static Item ofVariant(ProductSyncMapping mapping, ProductVariationSyncMapping variantMapping) {
            var variation = variantMapping.getVariation();
            return new Item(mapping.getId(), variantMapping.getId(), mapping.getProduct().getId(), variation.getId(),
                    variantMapping.getExternalVariantId(), variation.getStock(), variation.getPrice(), null,
                    variation.isTaxExempt(), variantMapping.getPosVersion(), mapping.isSyncStock(),
                    mapping.isSyncPrice(), false);
        }

        String describe() {
            return variationId == null ? "producto " + productId
                    : "la variación %d del producto %d".formatted(variationId, productId);
        }
    }

    private static final class Tally {
        int checked;
        int staleSnapshots;
        // Conjuntos y no listas (POS-5): un producto con dos tallas corregidas cuenta una vez. Ordenados por llegada,
        // para que los ids del registro salgan en el orden en que se revisaron.
        final Set<Long> stockCorrected = new LinkedHashSet<>();
        final Set<Long> catalogCorrected = new LinkedHashSet<>();
        final Set<Long> pendingInFlight = new LinkedHashSet<>();
        final Set<Long> changedMeanwhile = new LinkedHashSet<>();
        final Set<Long> missingInPos = new LinkedHashSet<>();
        final List<String> errors = new ArrayList<>();

        void fail(Item item, String what, RuntimeException e) {
            String message = "Producto %d: no se pudo conciliar el %s (%s)".formatted(item.productId(), what,
                    Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
            LOG.error(message, e);
            errors.add(message);
        }

        /** POS-5. Algo que el POS devolvió y no se puede aplicar: no es una excepción, pero hay que mirarlo. */
        void error(Item item, String reason) {
            String message = "Producto %d: %s".formatted(item.productId(), reason);
            LOG.error(message);
            errors.add(message);
        }

        PosReconciliationSummary toSummary(String failure) {
            List<String> allErrors = new ArrayList<>(errors);
            if (failure != null) {
                allErrors.addFirst(failure);
            }
            return new PosReconciliationSummary(checked, stockCorrected.size(), catalogCorrected.size(),
                    pendingInFlight.size(), changedMeanwhile.size(), missingInPos.size(), failure == null ? 0 : 1,
                    List.copyOf(allErrors));
        }
    }
}
