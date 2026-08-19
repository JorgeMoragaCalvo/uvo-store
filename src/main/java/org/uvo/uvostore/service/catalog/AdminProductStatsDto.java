package org.uvo.uvostore.service.catalog;

public record AdminProductStatsDto(long total, long active, long outOfStock, long lowStock) {
}
