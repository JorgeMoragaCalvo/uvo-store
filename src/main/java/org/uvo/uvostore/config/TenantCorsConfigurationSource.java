package org.uvo.uvostore.config;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.uvo.uvostore.security.StoreHostResolver;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;

// A2: replaces `allowedOriginPatterns(["*"]) + allowCredentials(true)` over /**, which told the
// browser that ANY origin may make credentialed cross-origin calls to the whole API. The direct
// impact was limited (the JWT travels in the Authorization header, not a cookie, so there's no
// ambient authority for another origin to ride on), but it removed CORS as a defence layer
// entirely.
//
// Now an origin is allowed only if its hostname resolves to a real store — the same custom-domain
// then subdomain-slug lookup TenantResolutionFilter does, shared via StoreHostResolver. A
// preflight is answered before the security filter chain runs, so TenantContext isn't available
// here and the Origin header has to be resolved directly.
//
// Returning null (no configuration) makes Spring omit the Access-Control-Allow-* headers, and the
// browser blocks the call. Same-origin requests carry no Origin header and never reach any of
// this.
@Component
public class TenantCorsConfigurationSource implements CorsConfigurationSource {

    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private final StoreHostResolver storeHostResolver;
    private final Set<String> additionalOrigins;

    public TenantCorsConfigurationSource(
            StoreHostResolver storeHostResolver,
            // Escape hatch for origins that aren't a store hostname: a separately hosted SPA build,
            // or Vite on another port. Normally empty — in dev, Vite proxies /api/* so the browser
            // sees same-origin requests and CORS never comes into play (see CLAUDE.md).
            @Value("${app.cors.additional-origins:}") String[] additionalOrigins) {
        this.storeHostResolver = storeHostResolver;
        this.additionalOrigins = Set.of(additionalOrigins);
    }

    @Override
    @Nullable
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return null;
        }
        if (!additionalOrigins.contains(origin) && !resolvesToAStore(origin)) {
            return null;
        }

        CorsConfiguration configuration = new CorsConfiguration();
        // The exact origin, not a pattern: with allowCredentials the response must name one origin,
        // and echoing only what we just verified is the point of the allowlist.
        configuration.setAllowedOrigins(List.of(origin));
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        return configuration;
    }

    private boolean resolvesToAStore(String origin) {
        try {
            String host = new URI(origin).getHost();
            return host != null && storeHostResolver.resolve(host).isPresent();
        } catch (URISyntaxException malformedOrigin) {
            return false;
        }
    }
}
