package org.uvo.uvostore.service.customer;

import java.time.Instant;

public record AdminCustomerSummaryDto(
        Long id, String email, String firstName, String lastName, String phone,
        String accountStatus, int ordersCount, Instant createdAt
) {
}
