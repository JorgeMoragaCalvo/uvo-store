package org.uvo.uvostore.service.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.uvo.uvostore.service.order.AdminOrderSummaryDto;

// Ports Admin\Customers\{CustomerIndex,CustomerShow}.
public interface AdminCustomerService {
    Page<AdminCustomerSummaryDto> search(String search, Pageable pageable);
    AdminCustomerStatsDto getStats();
    AdminCustomerDetailDto getById(Long id);
    Page<AdminOrderSummaryDto> getOrders(Long customerId, Pageable pageable);
    void deleteCustomer(Long id);
}
