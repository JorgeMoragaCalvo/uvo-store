package org.uvo.uvostore.service.payment;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

// M3: /api/v1/mercadopago/webhook is a public, unauthenticated endpoint that fires an outbound API
// call on every hit — a free lever against the merchant's API quota. The handler already refuses to
// trust the body (it re-queries MercadoPago), so a payment can't be forged, but nothing stopped the
// requests themselves.
//
// MercadoPago's scheme: the `x-signature` header carries `ts=<millis>,v1=<hmac>`, and the signed
// manifest is `id:<data.id>;request-id:<x-request-id>;ts:<ts>;` hashed with HMAC-SHA256 using the
// per-merchant webhook secret from their dashboard. Same shape as the POS webhook's HMAC and the
// platform key: constant-time comparison, no early exit that leaks how much matched.
@Component
public class MercadoPagoWebhookSignature {

    public boolean isValid(String signatureHeader, String requestId, String dataId, String secret) {
        if (signatureHeader == null || signatureHeader.isBlank() || secret == null || secret.isBlank()) {
            return false;
        }

        String ts = null;
        String v1 = null;
        for (String part : signatureHeader.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            if ("ts".equals(pair[0].trim())) {
                ts = pair[1].trim();
            } else if ("v1".equals(pair[0].trim())) {
                v1 = pair[1].trim();
            }
        }
        if (ts == null || v1 == null || dataId == null) {
            return false;
        }

        // The manifest's shape is fixed by MercadoPago, trailing semicolon included. request-id may
        // legitimately be absent, in which case its segment is empty rather than omitted.
        String manifest = "id:" + dataId + ";request-id:" + (requestId == null ? "" : requestId) + ";ts:" + ts + ";";
        return MessageDigest.isEqual(hmacSha256(manifest, secret), v1.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hmacSha256(String manifest, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)))
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular la firma del webhook de MercadoPago", e);
        }
    }
}
