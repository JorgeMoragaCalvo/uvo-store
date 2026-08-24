package org.uvo.uvostore.service.payment;

import java.util.Map;

// `credentials` is redacted on the way out (values replaced with a boolean "is it set" instead of
// the real secret) — admins can see WHICH keys are configured without the API ever echoing
// decrypted secrets back over the wire.
public record PaymentGatewayConfigDto(Long id, String gateway, boolean enabled, Map<String, Boolean> credentialsSet) {
}
