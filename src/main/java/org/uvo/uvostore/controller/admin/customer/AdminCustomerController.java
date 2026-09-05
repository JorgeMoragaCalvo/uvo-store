package org.uvo.uvostore.controller.admin.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.uvo.uvostore.service.customer.AdminCustomerDetailDto;
import org.uvo.uvostore.service.customer.AdminCustomerService;
import org.uvo.uvostore.service.customer.AdminCustomerStatsDto;
import org.uvo.uvostore.service.customer.AdminCustomerSummaryDto;
import org.uvo.uvostore.service.customer.CustomerAddressService;
import org.uvo.uvostore.service.customer.ShippingAddressDto;
import org.uvo.uvostore.service.order.AdminOrderSummaryDto;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Clientes (admin)", description = "Lectura, direcciones y estadísticas de clientes, JWT bearer con rol ADMIN")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/customers")
public class AdminCustomerController {

    private final AdminCustomerService adminCustomerService;
    private final CustomerAddressService customerAddressService;

    public AdminCustomerController(AdminCustomerService adminCustomerService, CustomerAddressService customerAddressService) {
        this.adminCustomerService = adminCustomerService;
        this.customerAddressService = customerAddressService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customers.view')")
    public Page<AdminCustomerSummaryDto> index(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "createdAt") String sortField,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(defaultValue = "1") int page
    ) {
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return adminCustomerService.search(search, PageRequest.of(Math.max(page - 1, 0), 20, Sort.by(direction, sortField)));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('customers.view')")
    public AdminCustomerStatsDto stats() {
        return adminCustomerService.getStats();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customers.view')")
    public AdminCustomerDetailDto show(@PathVariable Long id) {
        return adminCustomerService.getById(id);
    }

    @GetMapping("/{id}/orders")
    @PreAuthorize("hasAuthority('customers.view')")
    public Page<AdminOrderSummaryDto> orders(@PathVariable Long id, @RequestParam(defaultValue = "1") int page) {
        return adminCustomerService.getOrders(id, PageRequest.of(Math.max(page - 1, 0), 10, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('customers.manage')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        adminCustomerService.deleteCustomer(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{customerId}/addresses/{addressId}")
    @PreAuthorize("hasAuthority('customers.manage')")
    public ResponseEntity<Void> deleteAddress(@PathVariable Long customerId, @PathVariable Long addressId) {
        customerAddressService.deleteAddress(customerId, addressId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{customerId}/addresses/{addressId}/default")
    @PreAuthorize("hasAuthority('customers.manage')")
    public ShippingAddressDto setDefaultAddress(@PathVariable Long customerId, @PathVariable Long addressId) {
        return customerAddressService.setDefaultAddress(customerId, addressId);
    }
}
