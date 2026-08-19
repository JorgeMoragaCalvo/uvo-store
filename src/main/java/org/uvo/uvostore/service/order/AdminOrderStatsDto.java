package org.uvo.uvostore.service.order;

public record AdminOrderStatsDto(long all, long pending, long paid, long processing, long shipped, long cancelled) {
}
