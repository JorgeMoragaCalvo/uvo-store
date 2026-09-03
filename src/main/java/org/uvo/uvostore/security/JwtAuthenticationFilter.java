package org.uvo.uvostore.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenVersionService tokenVersionService;

    public JwtAuthenticationFilter(JwtService jwtService, TokenVersionService tokenVersionService) {
        this.jwtService = jwtService;
        this.tokenVersionService = tokenVersionService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length());
            try {
                Claims claims = jwtService.parseClaims(token);
                @SuppressWarnings("unchecked")
                List<String> authorityNames = claims.get("authorities", List.class);
                List<GrantedAuthority> authorities = authorityNames == null
                        ? List.of()
                        : authorityNames.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();

                Long uid = claims.get("uid", Number.class).longValue();
                Number sidClaim = claims.get("sid", Number.class);
                Long sid = sidClaim == null ? null : sidClaim.longValue();

                // Cross-check the token's store against the store resolved from this request's
                // subdomain (TenantResolutionFilter runs earlier in the chain) — a token issued
                // for one store must not authenticate a request against another store's subdomain.
                Long resolvedStoreId = TenantContext.currentStoreId();
                if (resolvedStoreId != null && sid != null && !resolvedStoreId.equals(sid)) {
                    SecurityContextHolder.clearContext();
                    filterChain.doFilter(request, response);
                    return;
                }

                // A5: a token stays valid only while it matches the principal's current version.
                // Deactivating, deleting or re-roling an account bumps that version, which is what
                // makes revocation possible at all — before this, a 24h token kept working with
                // its permissions frozen no matter what happened to the account behind it.
                // A missing claim means a token issued before this existed: treated as version 0,
                // the column default, so deploying doesn't log everyone out.
                String principalType = claims.get("type", String.class);
                Number tvClaim = claims.get("tv", Number.class);
                int tokenVersion = tvClaim == null ? 0 : tvClaim.intValue();
                if (tokenVersion != tokenVersionService.currentVersion(principalType, uid)) {
                    SecurityContextHolder.clearContext();
                    filterChain.doFilter(request, response);
                    return;
                }

                AuthPrincipal principal = new AuthPrincipal(uid, claims.getSubject(), principalType, sid);
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
