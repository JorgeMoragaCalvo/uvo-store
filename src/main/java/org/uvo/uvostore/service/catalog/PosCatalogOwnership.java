package org.uvo.uvostore.service.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.catalog.Product;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.enums.SyncStatus;
import org.uvo.uvostore.repository.ProductRepository;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.service.BusinessException;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * POS-2. Qué campos de un producto son de UvoPOS y, por tanto, no se editan en el panel.
 *
 * <p>Antes el panel no consultaba {@code ProductSyncMapping}: un administrador podía cambiar el precio o el
 * stock de un producto sincronizado, el cambio no llegaba al POS y la siguiente sincronización lo pisaba.
 *
 * <p><b>La regla es la del webhook {@code product.updated}</b> ({@code PosWebhookServiceImpl}), que es lo que
 * de verdad escribe el POS: solo cuentan los mapeos {@code ACTIVE}, y cada flag cubre lo mismo que allí
 * ({@code syncName} también el slug, {@code syncDescription} solo {@code description}). El SKU se bloquea con
 * cualquier mapeo activo, aunque no tenga flag: es la identidad del producto en el POS. Con dos empresas manda
 * la unión de sus flags.
 *
 * <p>No depende de {@code PosIntegrationPolicy}. Un mapeo solo nace por la superficie de entrada del POS, así
 * que el producto viene de allí aunque la integración esté apagada en este momento.
 *
 * <p>Lo que no está aquí sigue siendo de la web: la descripción corta (la "descripción comercial"), las
 * imágenes, el destacado, el estado, la categoría y los campos de meta y de oferta. Crear productos tampoco se
 * restringe: uno creado en el panel no tiene mapeo y es editable entero.
 */
@Service
public class PosCatalogOwnership {

    private final ProductSyncMappingRepository mappingRepository;
    private final ProductRepository productRepository;

    public PosCatalogOwnership(ProductSyncMappingRepository mappingRepository, ProductRepository productRepository) {
        this.mappingRepository = mappingRepository;
        this.productRepository = productRepository;
    }

    /** Lo que lee el formulario del panel para bloquear los campos. */
    @Transactional(readOnly = true)
    public PosOwnershipDto forProduct(Long productId) {
        // El filtro de tienda va aquí: la consulta de mapeos es por producto, sin tienda, y es este paso el que
        // impide preguntar por el producto de otra.
        Product product = productRepository.findById(productId)
                .filter(p -> p.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("Product " + productId + " not found"));
        return new PosOwnershipDto(lockedFields(product).stream().map(PosOwnedField::formField).toList());
    }

    public Set<PosOwnedField> lockedFields(Product product) {
        Set<PosOwnedField> locked = EnumSet.noneOf(PosOwnedField.class);
        for (ProductSyncMapping mapping : activeMappings(product.getId())) {
            locked.add(PosOwnedField.SKU);
            if (mapping.isSyncName()) {
                locked.add(PosOwnedField.NAME);
            }
            if (mapping.isSyncDescription()) {
                locked.add(PosOwnedField.DESCRIPTION);
            }
            if (mapping.isSyncPrice()) {
                locked.add(PosOwnedField.PRICE);
            }
            if (mapping.isSyncStock()) {
                // Sin gestión de stock la tienda vendería sin límite lo que el POS cuenta: va con el stock.
                locked.add(PosOwnedField.STOCK);
                locked.add(PosOwnedField.MANAGE_STOCK);
            }
        }
        return locked;
    }

    /**
     * Rechaza una actualización que cambie algún campo del POS y devuelve los bloqueados, para que quien
     * actualiza no los escriba. Hay que llamarlo <b>antes de tocar el producto</b>: la consulta de mapeos
     * vuelca la sesión, y un producto ya modificado se escribiría (o reventaría por el slug) antes del rechazo.
     *
     * <p>Un campo bloqueado que no llega ({@code null}) se conserva. Así lo usa el formulario, que no los envía:
     * un valor leído al cargar la página puede haber cambiado por una venta o por el POS, y reenviarlo sería un
     * rechazo falso. Un campo que llega igual al guardado también pasa, para quien reenvía el producto entero.
     */
    public Set<PosOwnedField> checkUpdate(Product stored, ProductCreateCommand command) {
        Set<PosOwnedField> locked = lockedFields(stored);
        List<PosOwnedField> changed = locked.stream()
                .filter(field -> !field.simpleOnly() || stored.getProductType() == ProductType.SIMPLE)
                .filter(field -> changes(field, stored, command))
                .toList();
        if (!changed.isEmpty()) {
            boolean plural = changed.size() > 1;
            throw new BusinessException("Este producto se sincroniza con UvoPOS: " + labels(changed)
                    + (plural ? " se cambian" : " se cambia") + " en el POS, no en el panel.");
        }
        return locked;
    }

    /**
     * Las variantes de un producto sincronizado reescribirían su precio y su stock
     * ({@code ProductVariationService.recalculateParentAggregate}), y el POS no las conoce: el mapeo es por
     * producto (POS-5).
     */
    public void requireNotMapped(Long productId) {
        if (!activeMappings(productId).isEmpty()) {
            throw new BusinessException("Este producto se sincroniza con UvoPOS y no admite variantes desde el "
                    + "panel: cambiarían su precio y su stock, que gestiona el POS.");
        }
    }

    // Lista y no Optional: un producto puede estar mapeado a dos empresas (PROD-13b).
    private List<ProductSyncMapping> activeMappings(Long productId) {
        return mappingRepository.findByProductIdIn(List.of(productId)).stream()
                .filter(mapping -> mapping.getSyncStatus() == SyncStatus.ACTIVE)
                .toList();
    }

    private static boolean changes(PosOwnedField field, Product stored, ProductCreateCommand command) {
        return switch (field) {
            case NAME -> differs(command.name(), stored.getName());
            case DESCRIPTION -> differs(command.description(), stored.getDescription());
            case SKU -> differs(command.sku(), stored.getSku());
            case PRICE -> differs(command.price(), stored.getPrice());
            case STOCK -> command.stock() != null && command.stock() != stored.getStock();
            case MANAGE_STOCK -> command.manageStock() != null && command.manageStock() != stored.isManageStock();
        };
    }

    // Vacío y null valen lo mismo: el formulario manda "" por un campo sin valor.
    private static boolean differs(String incoming, String stored) {
        return incoming != null && !Objects.equals(blankToNull(incoming), blankToNull(stored));
    }

    // compareTo y no equals: 9990 y 9990.00 son el mismo precio.
    private static boolean differs(BigDecimal incoming, BigDecimal stored) {
        return incoming != null && (stored == null || incoming.compareTo(stored) != 0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String labels(List<PosOwnedField> fields) {
        List<String> labels = fields.stream().map(PosOwnedField::label).toList();
        if (labels.size() == 1) {
            return labels.getFirst();
        }
        return String.join(", ", labels.subList(0, labels.size() - 1)) + " y " + labels.getLast();
    }
}
