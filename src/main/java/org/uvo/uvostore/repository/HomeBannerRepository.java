package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.settings.HomeBanner;

import java.util.List;

public interface HomeBannerRepository extends JpaRepository<HomeBanner, Long> {

    List<HomeBanner> findByActiveTrueOrderBySortOrderAsc(); // HomeBanner::getActiveBanners() cached in Laravel
}
