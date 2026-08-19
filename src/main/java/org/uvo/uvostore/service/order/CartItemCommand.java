package org.uvo.uvostore.service.order;

public record CartItemCommand(Long id, String type, int quantity) {
}
