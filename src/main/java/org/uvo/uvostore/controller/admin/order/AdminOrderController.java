package org.uvo.uvostore.controller.admin.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.order.AdminOrderDetailDto;
import org.uvo.uvostore.service.order.AdminOrderQueryService;
import org.uvo.uvostore.service.order.AdminOrderSearchCriteria;
import org.uvo.uvostore.service.order.AdminOrderService;
import org.uvo.uvostore.service.order.AdminOrderStatsDto;
import org.uvo.uvostore.service.order.AdminOrderSummaryDto;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Órdenes (admin)", description = "Gestión de órdenes de la tienda, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final AdminOrderQueryService adminOrderQueryService;
    private final AdminOrderService adminOrderService;

    public AdminOrderController(AdminOrderQueryService adminOrderQueryService, AdminOrderService adminOrderService) {
        this.adminOrderQueryService = adminOrderQueryService;
        this.adminOrderService = adminOrderService;
    }

    @GetMapping
    public Page<AdminOrderSummaryDto> index(
            @RequestParam(defaultValue = "all") String tab,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(defaultValue = "createdAt") String sortField,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(defaultValue = "1") int page
    ) {
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return adminOrderQueryService.search(
                new AdminOrderSearchCriteria(tab, search, paymentStatus),
                PageRequest.of(Math.max(page - 1, 0), 20, Sort.by(direction, sortField)));
    }

    @GetMapping("/stats")
    public AdminOrderStatsDto stats() {
        return adminOrderQueryService.getStats();
    }

    @GetMapping("/{id}")
    public AdminOrderDetailDto show(@PathVariable Long id) {
        return adminOrderQueryService.getById(id);
    }

    @PostMapping("/{id}/mark-processing")
    public AdminOrderDetailDto markProcessing(@PathVariable Long id) {
        return adminOrderService.markProcessing(id);
    }

    @PostMapping("/{id}/mark-shipped")
    public AdminOrderDetailDto markShipped(@PathVariable Long id) {
        return adminOrderService.markShipped(id);
    }

    @PostMapping("/{id}/mark-delivered")
    public AdminOrderDetailDto markDelivered(@PathVariable Long id) {
        return adminOrderService.markDelivered(id);
    }

    @PostMapping("/{id}/cancel")
    public AdminOrderDetailDto cancel(@PathVariable Long id) {
        return adminOrderService.cancelOrder(id);
    }

    @PutMapping("/{id}/status")
    public AdminOrderDetailDto updateStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        return adminOrderService.updateStatus(id, request.status());
    }

    @PutMapping("/{id}/payment-status")
    public AdminOrderDetailDto updatePaymentStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        return adminOrderService.updatePaymentStatus(id, request.status());
    }

    @PostMapping("/{id}/tracking")
    public AdminOrderDetailDto saveTracking(@PathVariable Long id, @RequestBody TrackingRequest request) {
        return adminOrderService.saveTracking(id, request.trackingNumber());
    }
}
