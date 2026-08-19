package org.uvo.uvostore.service.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminOrderQueryService {
    Page<AdminOrderSummaryDto> search(AdminOrderSearchCriteria criteria, Pageable pageable);
    AdminOrderStatsDto getStats();
    AdminOrderDetailDto getById(Long id);
}
