package org.uvo.uvostore.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

// Ports ValidatePosWebhook middleware: HMAC-SHA256(payload + timestamp, PosConnection.webhookSecret),
// a 5-minute timestamp freshness window, and the X-Signature/X-Company-ID/X-Timestamp headers.
// Only guards /api/webhooks/pos/** — every other path is passed through untouched.
//
// F21: y recuerda qué peticiones ya atendió, porque la frescura sola no basta. Dentro de esos cinco
// minutos una petición legítima capturada se puede reenviar tal cual: la firma sigue valiendo porque el
// cuerpo y el timestamp no han cambiado.
@Component
public class PosWebhookAuthFilter extends OncePerRequestFilter {

    private static final long FRESHNESS_WINDOW_SECONDS = 300;

    private final PosConnectionRepository posConnectionRepository;

    /**
     * F21. Las peticiones ya atendidas, por conexión.
     *
     * <p>No hace falta pedirle al POS un id de evento —ni cambiar el contrato con un codebase aparte, cuyo
     * contrato con el SII sigue sin confirmar—: <b>la propia firma ya es ese identificador</b>. Es
     * HMAC(cuerpo + timestamp, secreto), así que dos eventos legítimos distintos no pueden compartirla
     * (cambia el cuerpo o cambia el timestamp) y el cliente no puede elegirla sin el secreto.
     *
     * <p>Expira justo por encima de la ventana de frescura porque <b>eso es todo lo que hay que recordar</b>:
     * una petición más vieja ya la rechaza el control de timestamp antes de llegar aquí. El tope de tamaño
     * es por la misma razón que en {@code RateLimitFilter} y {@code AccountAttemptThrottle} — un control no
     * puede ser él mismo la vía de agotar la memoria.
     *
     * <p><b>Dos límites asumidos</b>: un reinicio dentro de esos 300 segundos pierde el guarda, y con más de
     * una instancia cada una recordaría lo suyo. Es la misma nota que {@code TokenVersionService} lleva para
     * su caché, y no hay despliegue multiinstancia todavía; el día que lo haya, esto se muda a una tabla con
     * {@code UNIQUE (company_id, signature_hash)}.
     */
    private final Cache<String, Boolean> seenRequests = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofSeconds(FRESHNESS_WINDOW_SECONDS + 10))
            .build();

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
        // dejaría que cualquiera quemase la firma auténtica —o llenase la caché de basura— sin conocer el
        // secreto. Solo se recuerda lo que ya demostró ser auténtico.
        //
        // La clave lleva el companyId porque el hallazgo pide unicidad "por conexión", pero conviene saber
        // que hoy es redundante: la firma ya sale del secreto de este comercio, así que dos comercios no
        // pueden coincidir en ella ni mandando el mismo cuerpo. Se queda porque es gratis y deja la
        // intención a la vista — si algún día dos conexiones compartieran secreto, esto seguiría
        // separándolas. Ningún test lo distingue, y es correcto: no hay forma de provocar esa colisión.
        String requestKey = connection.get().getCompanyId() + " " + signature;
        if (seenRequests.asMap().putIfAbsent(requestKey, Boolean.TRUE) != null) {
            // putIfAbsent es atómico, así que dos reenvíos simultáneos no pasan los dos.
            reject(response, "REPLAYED_WEBHOOK", "Webhook duplicado");
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
