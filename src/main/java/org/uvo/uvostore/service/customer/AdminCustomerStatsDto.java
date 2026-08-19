package org.uvo.uvostore.service.customer;

public record AdminCustomerStatsDto(long totalCustomers, long withOrders, long newThisMonth) {
}
