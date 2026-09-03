package org.uvo.uvostore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Resolves which Store a request belongs to from the Host header. The hostname -> Store lookup
// itself lives in StoreHostResolver, shared with the CORS allowlist (A2).
//
// Leaves TenantContext empty (not an error) when nothing resolves — routes that don't need a
// tenant this way (POS sync/webhooks, which resolve their store via companyId instead;
// actuator/health; swagger; platform onboarding, which creates the store) simply never read
// TenantContext, so they're unaffected.
@Component
public class TenantResolutionFilter extends OncePerRequestFilter {

    private final StoreHostResolver storeHostResolver;

    public TenantResolutionFilter(StoreHostResolver storeHostResolver) {
        this.storeHostResolver = storeHostResolver;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        try {
            storeHostResolver.resolve(request.getHeader("Host")).ifPresent(TenantContext::set);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
