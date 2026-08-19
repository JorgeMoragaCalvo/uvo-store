package org.uvo.uvostore.service.customer;

import java.time.Instant;
import java.util.List;

public record AdminCustomerDetailDto(
        Long id, String email, String firstName, String lastName, String phone, String accountStatus,
        List<ShippingAddressDto> addresses, AdminCustomerOrderStatsDto stats, Instant createdAt
) {
}
