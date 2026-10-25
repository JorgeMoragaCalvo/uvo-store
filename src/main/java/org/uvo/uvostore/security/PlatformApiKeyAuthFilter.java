package org.uvo.uvostore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.uvo.uvostore.config.RequiredSecret;
import org.uvo.uvostore.controller.advice.ApiError;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

// Guards /api/platform/** — store onboarding is done by the operator team, not by a per-store
// admin (there's no tenant/JWT to check against yet when a store doesn't exist). A single shared
// secret is enough for this: it's used by a handful of internal people, not exposed to clients.
// Same shape as PosApiKeyAuthFilter (constant-time compare, single header), different header name
// so the two schemes can't be confused with each other.
//
// POS-6: and only from the private network (PlatformNetworkPolicy). The network is checked BEFORE the
// key, so from outside the key isn't even evaluated: nobody on the Internet can try keys against it.
//
// PROD-18: con la clave correcta, deja en el contexto de seguridad un principal de plataforma con el operador
// que mande X-Platform-Operator (opcional; obligatorio en PROD-19). Es el actor de la auditoría. Orden de los
// rechazos: red (404) → clave (401) → operador mal formado (400).
@Component
public class PlatformApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String OPERATOR_HEADER = "X-Platform-Operator";
    // El tipo de AuthPrincipal de una llamada de plataforma; AuditTrail lo traduce a AuditActorType.PLATFORM.
    public static final String PRINCIPAL_TYPE = "PLATFORM";

    // Un id opaco del operador en el sistema que llama (el backoffice del POS): un número, un usuario o un correo.
    private static final Pattern OPERATOR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@-]{0,63}");

    private static final Logger log = LoggerFactory.getLogger(PlatformApiKeyAuthFilter.class);

    private final String platformApiKey;
    private final PlatformNetworkPolicy networkPolicy;
    private final ObjectMapper objectMapper;

    public PlatformApiKeyAuthFilter(@Value("${app.platform-api-key:}") String platformApiKey,
                                    PlatformNetworkPolicy networkPolicy, ObjectMapper objectMapper) {
        // Fails the startup, not the request: a blank key used to silently turn every
        // /api/platform/** call into a 401, so a missing env var only surfaced the day an operator
        // tried to onboard a store.
        RequiredSecret.require(platformApiKey, "PLATFORM_API_KEY");
        RequiredSecret.rejectLegacyDefault(platformApiKey, RequiredSecret.LEGACY_PLATFORM_API_KEY, "PLATFORM_API_KEY");
        this.platformApiKey = platformApiKey;
        this.networkPolicy = networkPolicy;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/platform/")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!networkPolicy.allows(request)) {
            log.warn("API de plataforma: petición rechazada desde una red no permitida (socket={})", request.getRemoteAddr());
            notFound(response);
            return;
        }

        String key = request.getHeader("X-Platform-Key");
        // No blank check on platformApiKey anymore — the constructor guarantees it's set.
        if (key == null || !constantTimeEquals(platformApiKey, key)) {
            reject(response);
            return;
        }

        // PROD-18. Después de la clave, a propósito: quien no la tiene no puede sondear este formato.
        List<String> operators = Collections.list(request.getHeaders(OPERATOR_HEADER));
        if (operators.size() > 1 || (operators.size() == 1 && !OPERATOR.matcher(operators.getFirst()).matches())) {
            badRequest(response);
            return;
        }
        String operator = operators.isEmpty() ? null : operators.getFirst();
        // El principal es lo que AuditTrail lee para saber quién actúa. Pisa el de un Bearer que viniera además
        // (JwtAuthenticationFilter corre antes, SecurityConfig): en esta ruta quien actúa es la plataforma.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(null, operator, PRINCIPAL_TYPE, null), null, List.of()));

        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"API key de plataforma inválida\"}");
    }

    // POS-6. 404 and not 403, same body as DeferredSurfaceGateFilter: from outside the private network
    // this API should look exactly like a route that was never written.
    private void notFound(HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.NOT_FOUND, "Not Found", "El recurso solicitado no existe.");
    }

    // PROD-18. Un operador mal formado es un error del cliente (el backoffice del POS), y se le dice: tragárselo
    // dejaría filas de auditoría sin operador sin que nadie se entere.
    private void badRequest(HttpServletResponse response) throws IOException {
        writeError(response, HttpStatus.BAD_REQUEST, "Bad Request", OPERATOR_HEADER
                + " inválido: un solo valor, de 1 a 64 caracteres (letras, números y . _ : @ -).");
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String error, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiError.of(status.value(), error, message));
    }
}
