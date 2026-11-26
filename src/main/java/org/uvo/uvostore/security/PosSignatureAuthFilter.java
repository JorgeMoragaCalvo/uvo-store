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
import org.uvo.uvostore.entity.pos.PosConnectionKey;
import org.uvo.uvostore.service.pos.PosConnectionKeyService;

import java.io.IOException;
import java.util.Optional;

/**
 * IMP-08. La autenticación de todo lo que el POS manda a la tienda: los webhooks ({@code /api/webhooks/pos/**}) y la
 * carga de catálogo ({@code /api/sync/product}). Implementa el §3 del contrato con la llave POS→tienda (INBOUND).
 *
 * <p>Reemplaza a los dos filtros del esquema anterior ({@code X-Signature} para los webhooks y Bearer {@code uvp_}
 * para la carga). Las reglas, en orden:
 *
 * <ol>
 *   <li>Con query string, 400: ningún endpoint del contrato la usa.</li>
 *   <li>{@code X-Uvo-Contract} distinto de {@code 1}, 400 {@code UNSUPPORTED_CONTRACT}.</li>
 *   <li>Falta una cabecera, el nonce no tiene el formato, el timestamp se aleja más de 300 s, o el {@code key_id} no
 *       se conoce, está revocado o retirado: 401.</li>
 *   <li>La firma, calculada sobre la ruta cruda y los bytes crudos <b>antes</b> de deserializar, no coincide: 401.</li>
 *   <li>{@code X-Company-ID} distinto de la empresa de la llave: 403 {@code FORBIDDEN_COMPANY}.</li>
 *   <li>La conexión está inactiva: 401.</li>
 *   <li>El nonce ya se vio con esa llave: 401. Se registra solo después de todo lo anterior.</li>
 * </ol>
 *
 * <p>Si todo pasa, deja la conexión en el atributo {@code posConnection}: de ahí sale la empresa
 * ({@link PosRequestGuard}), nunca del cuerpo. La tienda va al MDC, porque esta entrada no la resuelve por el Host.
 */
@Component
public class PosSignatureAuthFilter extends OncePerRequestFilter {

    public static final String CONNECTION_ATTRIBUTE = "posConnection";

    private final PosConnectionKeyService keys;
    private final PosWebhookReplayGuard replayGuard;

    public PosSignatureAuthFilter(PosConnectionKeyService keys, PosWebhookReplayGuard replayGuard) {
        this.keys = keys;
        this.replayGuard = replayGuard;
    }

    static boolean guards(String uri) {
        return uri.startsWith("/api/webhooks/pos/") || uri.startsWith("/api/sync/");
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !guards(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (request.getQueryString() != null) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "VALIDATION", "La petición no admite query string");
            return;
        }
        if (!UvoHmac.CONTRACT_VERSION.equals(request.getHeader(UvoHmac.HEADER_CONTRACT))) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "UNSUPPORTED_CONTRACT",
                    "Versión de contrato no admitida");
            return;
        }

        String keyId = request.getHeader(UvoHmac.HEADER_KEY_ID);
        String timestamp = request.getHeader(UvoHmac.HEADER_TIMESTAMP);
        String nonce = request.getHeader(UvoHmac.HEADER_NONCE);
        String signature = request.getHeader(UvoHmac.HEADER_SIGNATURE);
        String company = request.getHeader(UvoHmac.HEADER_COMPANY);
        if (keyId == null || timestamp == null || nonce == null || signature == null || company == null) {
            unauthorized(response, "Faltan cabeceras de firma");
            return;
        }
        if (!UvoHmac.isValidNonce(nonce)) {
            unauthorized(response, "Nonce inválido");
            return;
        }
        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            unauthorized(response, "Petición expirada");
            return;
        }
        if (Math.abs(System.currentTimeMillis() / 1000 - timestampSeconds) > UvoHmac.WINDOW_SECONDS) {
            unauthorized(response, "Petición expirada");
            return;
        }
        Optional<PosConnectionKey> key = keys.findUsableInbound(keyId);
        if (key.isEmpty()) {
            unauthorized(response, "Llave desconocida o revocada");
            return;
        }

        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        String canonical = UvoHmac.canonical(request.getMethod(), request.getRequestURI(), timestampSeconds, nonce,
                keyId, cached.getBody());
        if (!UvoHmac.verify(key.get().getSecret(), canonical, signature)) {
            unauthorized(response, "Firma inválida");
            return;
        }

        PosConnection connection = key.get().getConnection();
        if (!String.valueOf(connection.getCompanyId()).equals(company.trim())) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN_COMPANY",
                    "La empresa no corresponde a la llave");
            return;
        }
        if (!connection.isActive()) {
            unauthorized(response, "Conexión POS inactiva");
            return;
        }
        if (!replayGuard.firstTime(keyId, nonce)) {
            unauthorized(response, "Nonce repetido");
            return;
        }

        cached.setAttribute(CONNECTION_ATTRIBUTE, connection);
        LogContext.putStore(connection.getStore() == null ? null : connection.getStore().getId());
        try {
            filterChain.doFilter(cached, response);
        } finally {
            LogContext.removeStore();
        }
    }

    private static void unauthorized(HttpServletResponse response, String message) throws IOException {
        reject(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    /** El formato de error del contrato (§11). Los mensajes son fijos: nunca repiten lo recibido. */
    private static void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"details\":{}}}");
    }
}
