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

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
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

                AuthPrincipal principal = new AuthPrincipal(uid, claims.getSubject(), claims.get("type", String.class), sid);
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
