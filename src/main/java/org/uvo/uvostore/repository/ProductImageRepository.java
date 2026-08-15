package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.uvo.uvostore.entity.catalog.ProductImage;
import org.uvo.uvostore.entity.catalog.enums.ImageType;

import java.util.List;

@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByProductIdOrderBySortOrderAsc(Long productId); // ProductImage::ordered()
    List<ProductImage> findByProductIdAndIsFeaturedTrue(Long productId); // ::featured()
    List<ProductImage> findByProductIdAndType(Long productId, ImageType type); // ::gallery()
}
