package org.uvo.uvostore.service.pos;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.repository.ProductRepository;

import java.time.Instant;
import java.util.NoSuchElementException;

/**
 * POS-3. El único camino que escribe el catálogo de un producto mapeado, lo use el webhook
 * {@code product.updated} o la sincronización.
 *
 * <p>Antes eran dos caminos con reglas distintas: el webhook respetaba los flags del mapeo y la
 * sincronización pisaba nombre, descripción, precio, stock, estado y categoría en cada envío. Ninguno de los
 * dos miraba si lo que llegaba era más viejo que lo ya aplicado.
 *
 * <p><b>Qué escribe</b>: lo mismo que bloquea el panel ({@code PosCatalogOwnership}), y solo lo que viene.
 * {@code syncPrice} el precio, {@code syncName} el nombre y el slug, {@code syncDescription} la descripción.
 * El stock no pasa por aquí: lo mueve {@code stock.updated}, condicionado al {@code oldStock} (F13).
 *
 * <p><b>Orden</b>: la entrada trae la versión del POS, que crece con cada cambio del producto. Una igual o
 * menor que la aplicada se descarta. El {@code product.updated} tiene que traer todos los campos del POS y no
 * solo los cambiados: con una sola versión, un evento parcial más viejo que se descarta perdería su cambio
 * hasta la siguiente sincronización.
 */
@Component
public class PosCatalogWriter {

    public enum Outcome {
        APPLIED,
        /** La misma versión ya estaba aplicada: el POS la repitió. */
        ALREADY_APPLIED,
        /** Ya estaba aplicada una versión más nueva: el evento llegó fuera de orden. */
        STALE,
        /** El mapeo no está {@code ACTIVE}: no se escribe, igual que en el webhook de stock. */
        INACTIVE
    }

    public record Result(Outcome outcome, String message) {
    }

    private final ProductRepository productRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public PosCatalogWriter(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Aplica la entrada si es más nueva que lo aplicado. {@code MANDATORY}: el cerrojo solo dura lo que la
     * transacción de quien llama, y fuera de una no protegería nada.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result apply(ProductSyncMapping mapping, long version, PosCatalogFields fields) {
        // El cerrojo del producto ordena todas las entradas de catálogo de ese producto: la segunda espera a que
        // la primera confirme, y entonces relee la versión. Sin él, dos eventos simultáneos leen los dos la
        // versión vieja y gana el que confirma último, sea el que sea.
        //
        // Y protege el stock. Product no lleva @DynamicUpdate, así que el UPDATE de abajo escribe todas las
        // columnas, el stock incluido: una venta web entre la lectura y el commit volvía a aparecer. Con la
        // fila bloqueada, la venta (decrementStock) espera, y el stock que se escribe es el actual.
        //
        // El producto primero y el mapeo después, como en el webhook de stock (setStockIfUnchanged y luego
        // touchSynced): con el orden contrario, dos entradas podrían esperarse mutuamente.
        //
        // flush antes para no perder lo pendiente, y refresh después porque la consulta con cerrojo devuelve la
        // instancia que ya estaba en la sesión sin releerla (la trampa de PROD-13b): quien llama acaba de
        // cargar el mapeo, y puede haberlo cargado antes de que otra entrada confirmara.
        entityManager.flush();
        Long productId = mapping.getProduct().getId();
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new NoSuchElementException("Product " + productId + " not found"));
        entityManager.refresh(product);
        entityManager.refresh(mapping);

        if (mapping.getSyncStatus() != SyncStatus.ACTIVE) {
            return new Result(Outcome.INACTIVE, "El mapeo de este producto está " + mapping.getSyncStatus()
                    + ": no se aplica.");
        }

        Long applied = mapping.getCatalogVersion();
        if (applied != null && version <= applied) {
            return version == applied
                    ? new Result(Outcome.ALREADY_APPLIED, "Evento descartado: la versión %d ya estaba aplicada."
                            .formatted(version))
                    : new Result(Outcome.STALE, "Evento descartado: trae la versión %d y ya está aplicada la %d."
                            .formatted(version, applied));
        }

        if (mapping.isSyncPrice() && fields.price() != null) {
            product.setPrice(fields.price());
        }
        if (mapping.isSyncName() && fields.name() != null) {
            product.setName(fields.name());
            product.setSlug(slugify(fields.name()));
        }
        if (mapping.isSyncDescription() && fields.description() != null) {
            product.setDescription(fields.description());
        }

        // La versión avanza aunque ningún campo tuviera su flag: es lo último que se sabe del producto, y un
        // evento más viejo que llegue después no debe aplicarse.
        mapping.setCatalogVersion(version);
        mapping.setSyncError(null);
        mapping.setLastSyncedAt(Instant.now());
        return new Result(Outcome.APPLIED, null);
    }

    static String slugify(String value) {
        return value.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
