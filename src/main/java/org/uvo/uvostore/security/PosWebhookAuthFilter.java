package org.uvo.uvostore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.repository.PosConnectionRepository;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

// Ports ValidatePosWebhook middleware: HMAC-SHA256(payload + timestamp, PosConnection.webhookSecret),
// a 5-minute timestamp freshness window, and the X-Signature/X-Company-ID/X-Timestamp headers.
// Only guards /api/webhooks/pos/** — every other path is passed through untouched.
@Component
public class PosWebhookAuthFilter extends OncePerRequestFilter {

    private final PosConnectionRepository posConnectionRepository;

    public PosWebhookAuthFilter(PosConnectionRepository posConnectionRepository) {
        this.posConnectionRepository = posConnectionRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/webhooks/pos/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String signature = request.getHeader("X-Signature");
        String companyId = request.getHeader("X-Company-ID");
        String timestamp = request.getHeader("X-Timestamp");

        if (signature == null || companyId == null || timestamp == null) {
            reject(response, "MISSING_HEADERS", "Headers de webhook incompletos");
            return;
        }

        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            reject(response, "EXPIRED_WEBHOOK", "Webhook expirado");
            return;
        }
        if (Math.abs(System.currentTimeMillis() / 1000 - timestampSeconds) > 300) {
            reject(response, "EXPIRED_WEBHOOK", "Webhook expirado");
            return;
        }

        Optional<PosConnection> connection;
        try {
            connection = posConnectionRepository.findByCompanyId(Long.valueOf(companyId)).filter(PosConnection::isActive);
        } catch (NumberFormatException e) {
            connection = Optional.empty();
        }
        if (connection.isEmpty()) {
            reject(response, "POS_CONNECTION_NOT_FOUND", "Conexión POS no encontrada o inactiva");
            return;
        }

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String payload = new String(cachedRequest.getBody(), StandardCharsets.UTF_8);
        String expectedSignature = hmacSha256Hex(payload + timestamp, connection.get().getWebhookSecret());

        if (!constantTimeEquals(expectedSignature, signature)) {
            reject(response, "INVALID_SIGNATURE", "Firma de webhook inválida");
            return;
        }

        cachedRequest.setAttribute("posConnection", connection.get());
        filterChain.doFilter(cachedRequest, response);
    }

    private static String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular la firma HMAC", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response, String error, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\",\"error\":\"" + error + "\"}");
    }
}
