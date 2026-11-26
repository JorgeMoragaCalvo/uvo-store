package org.uvo.uvostore.service.pos;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.ProductVariation;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.ProductVariationSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.repository.OrderRepository;
import org.uvo.uvostore.repository.OutboxMessageRepository;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductVariationRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * POS-3 e IMP-08. El único camino que escribe lo que el POS manda de uno de sus Productos, venga de un webhook, de la
 * carga ({@code /api/sync/product}) o de la conciliación.
 *
 * <p><b>Qué escribe</b>: lo que es del POS (contrato §4) y solo lo que viene. {@code syncPrice} el precio,
 * {@code syncName} el nombre y el slug, {@code syncStock} el stock; la exención de IVA siempre. En una variante se
 * escribe la variación, y el padre vuelve a resumir sus variantes (el precio de la más barata, la suma del stock).
 *
 * <p><b>Orden</b> (§5): cada Producto del POS tiene una sola versión, que sube con cualquier cambio, de catálogo o de
 * stock. Una entrada con una versión igual o menor que la aplicada se descarta.
 *
 * <p><b>Stock</b> (§6): una corrección del POS no puede devolver unidades de ventas web cobradas que todavía no llegaron
 * al POS. Si el producto tiene alguna, el stock se salta y queda para la conciliación, que lo aplica cuando la venta
 * ya está en el POS (y acepta la misma versión). Lo demás de la entrada se aplica y la versión avanza.
 */
@Component
public class PosCatalogWriter {

    public enum Outcome {
        APPLIED,
        /** La misma versión ya estaba aplicada: el POS la repitió. */
        ALREADY_APPLIED,
        /** Ya estaba aplicada una versión más nueva: el evento llegó fuera de orden. */
        STALE,
        /** El mapeo no está {@code ACTIVE}: no se escribe. */
        INACTIVE,
        /** El id resuelve al padre de un producto con variantes, que no es un Producto del POS. */
        MISMATCH
    }

    /**
     * @param stockDeferred el stock no se aplicó porque había ventas web pendientes de llegar al POS
     */
    public record Result(Outcome outcome, String message, boolean stockDeferred) {

        Result(Outcome outcome, String message) {
            this(outcome, message, false);
        }
    }

    private final ProductRepository productRepository;
    private final ProductVariationRepository variationRepository;
    private final OutboxMessageRepository outboxRepository;
    private final OrderRepository orderRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public PosCatalogWriter(ProductRepository productRepository, ProductVariationRepository variationRepository,
                            OutboxMessageRepository outboxRepository, OrderRepository orderRepository) {
        this.productRepository = productRepository;
        this.variationRepository = variationRepository;
        this.outboxRepository = outboxRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * Aplica la entrada si es más nueva que lo aplicado. {@code MANDATORY}: el cerrojo solo dura lo que la transacción
     * de quien llama, y fuera de una no protegería nada.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result apply(PosTarget target, long version, PosCatalogFields fields) {
        return write(target, version, fields, false);
    }

    /**
     * POS-4. Lo mismo, para la conciliación: aplica también una versión <b>igual</b> a la aplicada. Si la lectura trae
     * la misma versión y los valores difieren, lo que se movió es lo de aquí, y el POS manda. Una versión menor sigue
     * descartándose: la lectura salió antes de que llegara un evento que ya se aplicó.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result reconcile(PosTarget target, long version, PosCatalogFields fields) {
        return write(target, version, fields, true);
    }

    private Result write(PosTarget target, long version, PosCatalogFields fields, boolean acceptSameVersion) {
        // El cerrojo del producto (el padre, en una variante) ordena todas las entradas de ese producto, y protege el
        // stock: Product y ProductVariation no llevan @DynamicUpdate, así que el UPDATE escribe todas las columnas, y
        // con la fila bloqueada una venta (decrementStock) espera y el stock que se escribe es el actual.
        //
        // flush antes para no perder lo pendiente, y refresh después porque la consulta con cerrojo devuelve la
        // instancia que ya estaba en la sesión sin releerla (la trampa de PROD-13b).
        entityManager.flush();
        Long productId = target.productId();
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new NoSuchElementException("Product " + productId + " not found"));
        entityManager.refresh(product);
        ProductSyncMapping mapping = target.mapping();
        entityManager.refresh(mapping);
        ProductVariationSyncMapping variantMapping = target instanceof PosTarget.Variant variant
                ? variant.variantMapping() : null;
        if (variantMapping != null) {
            entityManager.refresh(variantMapping);
            entityManager.refresh(variantMapping.getVariation());
        }

        if (mapping.getSyncStatus() != SyncStatus.ACTIVE) {
            return new Result(Outcome.INACTIVE, "El mapeo de este producto está " + mapping.getSyncStatus()
                    + ": no se aplica.");
        }
        if (variantMapping == null && product.getProductType() == ProductType.VARIABLE) {
            return new Result(Outcome.MISMATCH, ("El producto %d tiene variantes en la tienda: cada variante es un "
                    + "Producto del POS y se vincula por separado.").formatted(product.getId()));
        }

        Long applied = variantMapping != null ? variantMapping.getPosVersion() : mapping.getCatalogVersion();
        if (applied != null && (version < applied || (version == applied && !acceptSameVersion))) {
            return version == applied
                    ? new Result(Outcome.ALREADY_APPLIED, "Evento descartado: la versión %d ya estaba aplicada."
                            .formatted(version))
                    : new Result(Outcome.STALE, "Evento descartado: trae la versión %d y ya está aplicada la %d."
                            .formatted(version, applied));
        }

        boolean stockDeferred = false;
        if (variantMapping != null) {
            ProductVariation variation = variantMapping.getVariation();
            if (mapping.isSyncPrice() && fields.price() != null && variation.getPrice().compareTo(fields.price()) != 0) {
                variation.setPrice(fields.price());
            }
            if (fields.taxExempt() != null) {
                variation.setTaxExempt(fields.taxExempt());
            }
            if (mapping.isSyncStock() && fields.stockWeb() != null && fields.stockWeb() != variation.getStock()) {
                if (hasPendingSales(mapping.getCompanyId(), productId)) {
                    stockDeferred = true;
                } else {
                    variation.setStock(fields.stockWeb());
                }
            }
            recalculateParent(product);
            variantMapping.setPosVersion(version);
        } else {
            if (mapping.isSyncPrice() && fields.price() != null) {
                product.setPrice(fields.price());
            }
            // POS-6. El slug solo cambia si cambia el nombre, y nunca a uno que ya use otro producto de la tienda.
            if (mapping.isSyncName() && fields.name() != null && !fields.name().equals(product.getName())) {
                product.setName(fields.name());
                product.setSlug(uniqueProductSlug(product.getStore().getId(), fields.name(), product.getId()));
            }
            if (fields.taxExempt() != null) {
                product.setTaxExempt(fields.taxExempt());
            }
            if (mapping.isSyncStock() && fields.stockWeb() != null && fields.stockWeb() != product.getStock()) {
                if (hasPendingSales(mapping.getCompanyId(), productId)) {
                    stockDeferred = true;
                } else {
                    product.setStock(fields.stockWeb());
                }
            }
            mapping.setCatalogVersion(version);
        }

        // La versión avanza aunque ningún campo tuviera su flag: es lo último que se sabe del producto, y un evento más
        // viejo que llegue después no debe aplicarse.
        mapping.setSyncError(null);
        mapping.setLastSyncedAt(Instant.now());
        return new Result(Outcome.APPLIED, stockDeferred
                ? "Stock no aplicado: el producto tiene ventas web que el POS todavía no registró."
                : null, stockDeferred);
    }

    /**
     * §6. Si el producto tiene ventas web cobradas que esta empresa del POS todavía no registró (su fila del outbox no
     * se entregó) o cuyo stock todavía no se descontó. Las mismas dos consultas que la foto de la conciliación. Por
     * producto, no por variante: una venta pendiente de una talla retrasa las demás, que es lo prudente.
     */
    boolean hasPendingSales(Long companyId, Long productId) {
        List<Long> ids = List.of(productId);
        return !outboxRepository.findProductsWithUnregisteredSales(companyId, ids).isEmpty()
                || !orderRepository.findProductsWithStockPending(ids).isEmpty();
    }

    /**
     * El padre de un producto con variantes resume sus variaciones: el stock es la suma de todas, con la misma regla
     * que {@code ProductRepository.recalculateStockFromVariations}, y el precio es el de la variante activa más barata,
     * que es el "desde" de la tienda. Corre con el padre bloqueado, así que ninguna variación cambia mientras tanto.
     */
    private void recalculateParent(Product product) {
        List<ProductVariation> variations = variationRepository.findByProductId(product.getId());
        product.setStock(variations.stream().mapToInt(ProductVariation::getStock).sum());
        variations.stream()
                .filter(ProductVariation::isActive)
                .map(ProductVariation::getPrice)
                .min(BigDecimal::compareTo)
                .ifPresent(product::setPrice);
    }

    static String slugify(String value) {
        return value.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    /**
     * POS-6. Un slug libre en la tienda para un producto que viene del POS: el del nombre, o {@code -2}, {@code -3}…
     * si ya lo usa otro. Un nombre que no deja nada da {@code producto}. El propio producto no cuenta al renombrar
     * ({@code selfId}), para que conserve su slug si sigue libre.
     */
    String uniqueProductSlug(Long storeId, String name, Long selfId) {
        String base = slugify(name);
        if (base.isEmpty()) {
            base = "producto";
        }
        String candidate = base;
        for (int suffix = 2; slugTaken(storeId, candidate, selfId); suffix++) {
            candidate = base + "-" + suffix;
        }
        return candidate;
    }

    private boolean slugTaken(Long storeId, String slug, Long selfId) {
        return selfId == null
                ? productRepository.existsByStoreIdAndSlug(storeId, slug)
                : productRepository.existsByStoreIdAndSlugAndIdNot(storeId, slug, selfId);
    }
}
