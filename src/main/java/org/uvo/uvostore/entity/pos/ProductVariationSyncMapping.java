package org.uvo.uvostore.entity.pos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;
import org.uvo.uvostore.entity.catalog.ProductVariation;

/**
 * POS-5. Qué variación de la web es cada variante de un producto del POS.
 *
 * <p>Cuelga del mapeo del producto ({@link ProductSyncMapping}), que es el que lleva la empresa, los flags y la
 * versión de catálogo: aquí solo está la identidad de la variante en el POS.
 */
@Entity
@Table(name = "product_variation_sync_mapping")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ProductVariationSyncMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mapping_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ProductSyncMapping mapping;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_variation_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ProductVariation variation;

    // El id de la variante en el POS. Único dentro del mapeo del producto, no globalmente: es de una empresa.
    @Column(name = "external_variant_id", nullable = false)
    private Long externalVariantId;

    @Column(name = "external_sku", nullable = false, length = 100)
    private String externalSku;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
