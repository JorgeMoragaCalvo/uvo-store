package org.uvo.uvostore.entity.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Explicit join entity for the Laravel pivot-like table product_variation_attributes,
 * which carries attribute_id alongside attribute_value_id (not bare many-to-many).
 * A variation may only have one value per attribute — enforced by the unique constraint
 * below, matching the real DB constraint name pva_var_attr_uniq.
 */
@Entity
@Table(
        name = "product_variation_attributes",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "pva_var_attr_uniq",
                        columnNames = {"product_variation_id", "attribute_id"}),
        indexes =
                @Index(
                        name = "pva_var_attrval_idx",
                        columnList = "product_variation_id, attribute_value_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ProductVariationAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_variation_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ProductVariation variation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Attribute attribute;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_value_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AttributeValue attributeValue;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
