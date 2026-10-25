package org.uvo.uvostore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.uvo.uvostore.config.LogContext;
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
//
// F21: y recuerda qué peticiones ya atendió, porque la frescura sola no basta. Dentro de esos cinco
// minutos una petición legítima capturada se puede reenviar tal cual: la firma sigue valiendo porque el
// cuerpo y el timestamp no han cambiado.
//
// No hace falta pedirle al POS un id de evento: la propia firma ya es ese identificador. Es
// HMAC(cuerpo + timestamp, secreto), así que dos eventos legítimos distintos no pueden compartirla (cambia el
// cuerpo o cambia el timestamp) y el cliente no puede elegirla sin el secreto.
//
// POS-3: lo atendido se guarda en la base (PosWebhookReplayGuard), no en una caché en memoria, así que el
// guarda sobrevive a un reinicio y vale igual con varias instancias.
@Component
public class PosWebhookAuthFilter extends OncePerRequestFilter {

    static final long FRESHNESS_WINDOW_SECONDS = 300;

    private final PosConnectionRepository posConnectionRepository;
    private final PosWebhookReplayGuard replayGuard;

    public PosWebhookAuthFilter(PosConnectionRepository posConnectionRepository, PosWebhookReplayGuard replayGuard) {
        this.posConnectionRepository = posConnectionRepository;
        this.replayGuard = replayGuard;
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
        if (Math.abs(System.currentTimeMillis() / 1000 - timestampSeconds) > FRESHNESS_WINDOW_SECONDS) {
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

        // F21. El registro va DESPUÉS de verificar la firma, y el orden no es cosmético: recordar antes
        // dejaría que cualquiera quemase la firma auténtica —o llenase la tabla de basura— sin conocer el
        // secreto. Solo se recuerda lo que ya demostró ser auténtico.
        //
        // La clave lleva el companyId porque el hallazgo pide unicidad "por conexión", pero conviene saber
        // que hoy es redundante: la firma ya sale del secreto de este comercio, así que dos comercios no
        // pueden coincidir en ella ni mandando el mismo cuerpo. Se queda porque es gratis y deja la
        // intención a la vista — si algún día dos conexiones compartieran secreto, esto seguiría
        // separándolas. Ningún test lo distingue, y es correcto: no hay forma de provocar esa colisión.
        if (!replayGuard.firstTime(connection.get().getCompanyId(), signature)) {
            // Un INSERT … ON CONFLICT DO NOTHING contra el índice único, así que dos reenvíos simultáneos no
            // pasan los dos.
            reject(response, "REPLAYED_WEBHOOK", "Webhook duplicado");
            return;
        }

        cachedRequest.setAttribute("posConnection", connection.get());
        // PROD-15b. Como en PosApiKeyAuthFilter: la tienda sale de la conexión, no del Host.
        LogContext.putStore(PosApiKeyAuthFilter.storeIdOf(connection.get()));
        try {
            filterChain.doFilter(cachedRequest, response);
        } finally {
            LogContext.removeStore();
        }
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
