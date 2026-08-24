package org.uvo.uvostore.service.payment;

import java.util.Map;

public record PaymentGatewayConfigCommand(boolean enabled, Map<String, String> credentials) {
}
