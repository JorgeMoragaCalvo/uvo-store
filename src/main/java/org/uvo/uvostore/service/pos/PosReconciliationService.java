package org.uvo.uvostore.service.pos;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.SyncWebhookLog;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.repository.PosConnectionRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.service.BusinessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 */
@Service
public class PosReconciliationService {

    private static final Logger LOG = LoggerFactory.getLogger(PosReconciliationService.class);

    /** Cuántos ids de cada tipo se guardan en el registro: el conteo va siempre entero. */
    static final int MAX_IDS_IN_LOG = 50;

    public enum Trigger {
        SCHEDULED, MANUAL
    }

    private final PosConnectionRepository connectionRepository;
    private final ProductSyncMappingRepository mappingRepository;
    private final ProductRepository productRepository;
    private final OutboxMessageRepository outboxRepository;
    private final OrderRepository orderRepository;
    private final PosClient posClient;
    private final PosCatalogWriter catalogWriter;
    private final PosInboundLog inboundLog;
    private final PosIntegrationPolicy posPolicy;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnlyTransactions;
    private final int batchSize;

    public PosReconciliationService(PosConnectionRepository connectionRepository,
                                    ProductSyncMappingRepository mappingRepository,
                                    ProductRepository productRepository, OutboxMessageRepository outboxRepository,
                                    OrderRepository orderRepository, PosClient posClient,
                                    PosCatalogWriter catalogWriter, PosInboundLog inboundLog,
                                    PosIntegrationPolicy posPolicy, PlatformTransactionManager transactionManager,
                                    @Value("${app.pos.reconciliation.batch-size:200}") int batchSize) {
        this.connectionRepository = connectionRepository;
        this.mappingRepository = mappingRepository;
        this.productRepository = productRepository;
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
            throw new BusinessException("Esta tienda no tiene una conexión activa con UvoPOS: configura el token "
                    + "de la API antes de resincronizar.");
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
        while (true) {
            long cursor = afterId;
            Batch batch = readOnlyTransactions.execute(status -> snapshot(connection.getCompanyId(), cursor));
            if (batch == null || batch.items().isEmpty()) {
                break;
            }

            PosCatalogSnapshot pos = posClient.readCatalog(connection,
                    batch.items().stream().map(Item::externalId).toList());
            if (pos.isFailed()) {
                // Sin la lectura no hay con qué comparar. Se corta esta conexión; las demás siguen.
                failure = pos.failure();
                break;
            }

            for (Item item : batch.items()) {
                reconcileItem(item, pos.rows().get(item.externalId()), batch.inFlight(), tally);
            }
            afterId = batch.items().getLast().mappingId();
            if (batch.items().size() < batchSize) {
                break;
            }
        }

        record(connection, trigger, tally, failure);
        alert(connection, tally, failure);
        return tally.toSummary(failure);
    }

    /** La foto del lote: valores, no entidades, porque las correcciones limpian el contexto de persistencia. */
    private Batch snapshot(Long companyId, long afterId) {
        List<Item> items = mappingRepository.findActiveBatch(companyId, afterId, PageRequest.of(0, batchSize))
                .stream()
                .map(Item::of)
                .toList();
        if (items.isEmpty()) {
            return new Batch(items, Set.of());
        }
        List<Long> productIds = items.stream().map(Item::productId).toList();
        Set<Long> inFlight = new HashSet<>(outboxRepository.findProductsWithUnregisteredSales(companyId, productIds));
        inFlight.addAll(orderRepository.findProductsWithStockPending(productIds));
        return new Batch(items, inFlight);
    }

    private void reconcileItem(Item item, PosCatalogSnapshot.Row row, Set<Long> inFlight, Tally tally) {
        tally.checked++;
        if (row == null) {
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
            int updated = productRepository.setStockIfUnchanged(item.productId(), item.stock(), row.stockWeb());
            if (updated == 1) {
                mappingRepository.touchSynced(item.mappingId(), Instant.now());
            }
            return updated == 1;
        });
        if (Boolean.TRUE.equals(corrected)) {
            LOG.warn("Conciliación con el POS: producto {} tenía stock {} y el POS dice {}; corregido",
                    item.productId(), item.stock(), row.stockWeb());
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
                || (item.syncName() && row.name() != null && !row.name().equals(item.name()))
                || (item.syncDescription() && row.description() != null
                        && !row.description().equals(item.description()));
        boolean newer = item.catalogVersion() == null || row.version() > item.catalogVersion();
        if (!differs && !newer) {
            return;
        }

        PosCatalogWriter.Result result = transactions.execute(status -> mappingRepository.findById(item.mappingId())
                .map(mapping -> catalogWriter.reconcile(mapping, row.version(),
                        new PosCatalogFields(row.name(), row.description(), row.price())))
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
            // Se pausó mientras tanto, o no puede pasar con reconcile: nada que contar.
            case INACTIVE, ALREADY_APPLIED -> {
            }
        }
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

    private static void putIds(Map<String, Object> payload, String key, List<Long> ids) {
        payload.put(key, ids.size());
        payload.put(key + "_ids", List.copyOf(ids.subList(0, Math.min(ids.size(), MAX_IDS_IN_LOG))));
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

    private record Batch(List<Item> items, Set<Long> inFlight) {
    }

    private record Item(Long mappingId, Long productId, Long externalId, int stock, BigDecimal price, String name,
                        String description, Long catalogVersion, boolean syncStock, boolean syncPrice,
                        boolean syncName, boolean syncDescription) {

        static Item of(ProductSyncMapping mapping) {
            var product = mapping.getProduct();
            return new Item(mapping.getId(), product.getId(), mapping.getExternalId(), product.getStock(),
                    product.getPrice(), product.getName(), product.getDescription(), mapping.getCatalogVersion(),
                    mapping.isSyncStock(), mapping.isSyncPrice(), mapping.isSyncName(), mapping.isSyncDescription());
        }
    }

    private static final class Tally {
        int checked;
        int staleSnapshots;
        final List<Long> stockCorrected = new ArrayList<>();
        final List<Long> catalogCorrected = new ArrayList<>();
        final List<Long> pendingInFlight = new ArrayList<>();
        final List<Long> changedMeanwhile = new ArrayList<>();
        final List<Long> missingInPos = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        void fail(Item item, String what, RuntimeException e) {
            String message = "Producto %d: no se pudo conciliar el %s (%s)".formatted(item.productId(), what,
                    Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
            LOG.error(message, e);
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
