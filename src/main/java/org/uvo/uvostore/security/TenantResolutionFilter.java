package org.uvo.uvostore.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.uvo.uvostore.repository.StoreRepository;

import java.io.IOException;

// Resolves which Store a request belongs to from the subdomain of the Host header
// (e.g. "demo.uvostore.cl" -> slug "demo"). Locally, "demo.localhost:8080" resolves the same
// way without any hosts-file changes, since *.localhost already points at 127.0.0.1 in modern
// browsers/OS resolvers.
//
// Leaves TenantContext empty (not an error) when the host has no subdomain — routes that don't
// need a tenant this way (POS sync/webhooks, which resolve their store via companyId instead;
// actuator/health; swagger) simply never read TenantContext, so they're unaffected.
@Component
public class TenantResolutionFilter extends OncePerRequestFilter {

    private final StoreRepository storeRepository;

    public TenantResolutionFilter(StoreRepository storeRepository) {
        this.storeRepository = storeRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String slug = extractSlug(request.getHeader("Host"));
            if (slug != null) {
                storeRepository.findBySlug(slug).ifPresent(TenantContext::set);
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String extractSlug(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String hostname = host.split(":")[0];
        int firstDot = hostname.indexOf('.');
        if (firstDot <= 0) {
            return null; // no subdomain: bare "localhost", an IP, etc.
        }
        return hostname.substring(0, firstDot);
    }
}
