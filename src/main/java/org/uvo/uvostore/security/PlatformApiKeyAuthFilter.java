package org.uvo.uvostore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// Guards /api/platform/** — store onboarding is done by the operator team, not by a per-store
// admin (there's no tenant/JWT to check against yet when a store doesn't exist). A single shared
// secret is enough for this: it's used by a handful of internal people, not exposed to clients.
// Same shape as PosApiKeyAuthFilter (constant-time compare, single header), different header name
// so the two schemes can't be confused with each other.
@Component
public class PlatformApiKeyAuthFilter extends OncePerRequestFilter {

    private final String platformApiKey;

    public PlatformApiKeyAuthFilter(@Value("${app.platform-api-key:}") String platformApiKey) {
        this.platformApiKey = platformApiKey;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/platform/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = request.getHeader("X-Platform-Key");
        if (platformApiKey.isBlank() || key == null || !constantTimeEquals(platformApiKey, key)) {
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
        response.getWriter().write("{\"message\":\"API key de plataforma inválida o no configurada\"}");
    }
}
