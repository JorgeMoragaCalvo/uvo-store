package org.uvo.uvostore.controller.admin.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
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
import org.uvo.uvostore.service.payment.RefundCommand;
import org.uvo.uvostore.service.payment.RefundService;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Órdenes (admin)", description = "Gestión de órdenes de la tienda, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    // M8: sortField came straight from the query string into Sort.by(...), so any name that isn't a
    // real property blew up as a 500 (PropertyReferenceException) and every column of the entity was
    // orderable, including the password hash on the user listing. Same allowlist-with-silent-fallback that
    // ProductController:25,52 already used on the public side.
    private static final java.util.Set<String> ALLOWED_SORTS = java.util.Set.of("createdAt", "orderNumber", "total", "status", "paymentStatus", "customerEmail");

    private final AdminOrderQueryService adminOrderQueryService;
    private final AdminOrderService adminOrderService;
    private final RefundService refundService;

    public AdminOrderController(AdminOrderQueryService adminOrderQueryService, AdminOrderService adminOrderService,
                                RefundService refundService) {
        this.adminOrderQueryService = adminOrderQueryService;
        this.adminOrderService = adminOrderService;
        this.refundService = refundService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('orders.view')")
    public Page<AdminOrderSummaryDto> index(
            @RequestParam(defaultValue = "all") String tab,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(defaultValue = "createdAt") String sortField,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(defaultValue = "1") int page
    ) {
        String safeSort = ALLOWED_SORTS.contains(sortField) ? sortField : "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return adminOrderQueryService.search(
                new AdminOrderSearchCriteria(tab, search, paymentStatus),
                PageRequest.of(Math.max(page - 1, 0), 20, Sort.by(direction, safeSort)));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('orders.view')")
    public AdminOrderStatsDto stats() {
        return adminOrderQueryService.getStats();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('orders.view')")
    public AdminOrderDetailDto show(@PathVariable Long id) {
        return adminOrderQueryService.getById(id);
    }

    @PostMapping("/{id}/mark-processing")
    @PreAuthorize("hasAuthority('orders.manage')")
    public AdminOrderDetailDto markProcessing(@PathVariable Long id) {
        return adminOrderService.markProcessing(id);
    }

    @PostMapping("/{id}/mark-shipped")
    @PreAuthorize("hasAuthority('orders.manage')")
    public AdminOrderDetailDto markShipped(@PathVariable Long id) {
        return adminOrderService.markShipped(id);
    }

    @PostMapping("/{id}/mark-delivered")
    @PreAuthorize("hasAuthority('orders.manage')")
    public AdminOrderDetailDto markDelivered(@PathVariable Long id) {
        return adminOrderService.markDelivered(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('orders.manage')")
    public AdminOrderDetailDto cancel(@PathVariable Long id) {
        return adminOrderService.cancelOrder(id);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('orders.manage')")
    public AdminOrderDetailDto updateStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        return adminOrderService.updateStatus(id, request.status());
    }

    @PutMapping("/{id}/payment-status")
    @PreAuthorize("hasAuthority('orders.manage')")
    public AdminOrderDetailDto updatePaymentStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        return adminOrderService.updatePaymentStatus(id, request.status());
    }

    @PostMapping("/{id}/tracking")
    @PreAuthorize("hasAuthority('orders.manage')")
    public AdminOrderDetailDto saveTracking(@PathVariable Long id, @RequestBody TrackingRequest request) {
        return adminOrderService.saveTracking(id, request.trackingNumber());
    }

    // G4. Permiso propio y no 'orders.manage': ese lo tiene cualquiera que despache pedidos, y esto
    // mueve dinero de vuelta. V19 se lo concede a quien ya tenga orders.manage, así que nadie pierde
    // acceso el día del despliegue — pero a partir de ahí se puede quitar por separado.
    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAuthority('orders.refund')")
    public AdminOrderDetailDto refund(@PathVariable Long id, @RequestBody @jakarta.validation.Valid RefundRequest request,
                                      org.springframework.security.core.Authentication authentication) {
        refundService.refund(new RefundCommand(id, request.amount(), request.reason(), currentUserId(authentication)));
        return adminOrderQueryService.getById(id);
    }

    /**
     * Registra un reembolso que ya se hizo en el panel de la pasarela. No mueve dinero: solo evita
     * que la base siga diciendo que la orden está cobrada cuando ya no lo está.
     */
    @PostMapping("/{id}/refund/external")
    @PreAuthorize("hasAuthority('orders.refund')")
    public AdminOrderDetailDto recordExternalRefund(@PathVariable Long id, @RequestBody @jakarta.validation.Valid RefundRequest request,
                                                    org.springframework.security.core.Authentication authentication) {
        refundService.recordExternal(new RefundCommand(id, request.amount(), request.reason(), currentUserId(authentication)));
        return adminOrderQueryService.getById(id);
    }

    private Long currentUserId(org.springframework.security.core.Authentication authentication) {
        return ((org.uvo.uvostore.security.AuthPrincipal) authentication.getPrincipal()).id();
    }
}
