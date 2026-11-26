package org.uvo.uvostore.service.pos;

import org.uvo.uvostore.entity.pos.ProductSyncMapping;
import org.uvo.uvostore.entity.pos.ProductVariationSyncMapping;

/**
 * IMP-08. Lo que es en la tienda un Producto del POS (contrato §4): un producto simple o una variante de un producto
 * con variantes. Lo resuelve {@link PosProductResolver}.
 */
public sealed interface PosTarget {

    /** El mapeo del producto: el propio en un simple, el del padre en una variante. Lleva la empresa y los flags. */
    ProductSyncMapping mapping();

    /** El producto de la tienda que se bloquea: el simple, o el padre de la variante. */
    default Long productId() {
        return mapping().getProduct().getId();
    }

    record Simple(ProductSyncMapping mapping) implements PosTarget {
    }

    record Variant(ProductVariationSyncMapping variantMapping) implements PosTarget {

        @Override
        public ProductSyncMapping mapping() {
            return variantMapping.getMapping();
        }

        @Override
        public Long productId() {
            return variantMapping.getVariation().getProduct().getId();
        }
    }
}
