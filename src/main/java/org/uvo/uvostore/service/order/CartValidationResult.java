package org.uvo.uvostore.service.order;

import java.util.List;
import java.util.Map;

public record CartValidationResult(boolean valid, List<CartValidatedItemDto> items, Map<String, String> errors) {
}
