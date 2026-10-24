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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.uvo.uvostore.config.RequiredSecret;
import org.uvo.uvostore.controller.advice.ApiError;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// Guards /api/platform/** — store onboarding is done by the operator team, not by a per-store
// admin (there's no tenant/JWT to check against yet when a store doesn't exist). A single shared
// secret is enough for this: it's used by a handful of internal people, not exposed to clients.
// Same shape as PosApiKeyAuthFilter (constant-time compare, single header), different header name
// so the two schemes can't be confused with each other.
//
// POS-6: and only from the private network (PlatformNetworkPolicy). The network is checked BEFORE the
// key, so from outside the key isn't even evaluated: nobody on the Internet can try keys against it.
@Component
public class PlatformApiKeyAuthFilter extends OncePerRequestFilter {

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
        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiError.of(HttpStatus.NOT_FOUND.value(), "Not Found", "El recurso solicitado no existe."));
    }
}
