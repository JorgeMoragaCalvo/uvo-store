package org.uvo.uvostore.service.pos;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.catalog.enums.ProductType;
import org.uvo.uvostore.repository.ProductSyncMappingRepository;
import org.uvo.uvostore.repository.ProductVariationSyncMappingRepository;

import java.util.Optional;

/**
 * IMP-08. Qué es en la tienda un Producto del POS de una empresa (contrato §4): una variante vinculada, o un producto
 * simple mapeado. El padre de un producto con variantes no es un Producto del POS y no se resuelve nunca.
 *
 * <p>La unicidad entre las dos tablas no la puede garantizar la base (cada una es única por empresa por su lado);
 * la mantiene {@code PosVariantLinkService}, que no deja vincular un id que ya usa un producto simple.
 */
@Component
public class PosProductResolver {

    private final ProductSyncMappingRepository mappingRepository;
    private final ProductVariationSyncMappingRepository variantMappingRepository;

    public PosProductResolver(ProductSyncMappingRepository mappingRepository,
                              ProductVariationSyncMappingRepository variantMappingRepository) {
        this.mappingRepository = mappingRepository;
        this.variantMappingRepository = variantMappingRepository;
    }

    public Optional<PosTarget> resolve(Long companyId, Long posProductId) {
        if (posProductId == null) {
            return Optional.empty();
        }
        Optional<PosTarget> variant = variantMappingRepository.findByCompanyIdAndExternalId(companyId, posProductId)
                .map(PosTarget.Variant::new);
        if (variant.isPresent()) {
            return variant;
        }
        return mappingRepository.findByExternalIdAndCompanyId(posProductId, companyId)
                .filter(mapping -> mapping.getProduct().getProductType() != ProductType.VARIABLE)
                .map(PosTarget.Simple::new);
    }
}
