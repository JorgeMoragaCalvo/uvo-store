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
import java.util.Optional;

// Resolves which Store a request belongs to from the Host header, in two steps:
//   1. Exact match of the whole hostname against Store.domain — a client's own custom domain
//      (e.g. "tiendadejuan.cl"), the primary path once a client's DNS is pointed at us.
//   2. Falling back to the subdomain-slug scheme (e.g. "demo.uvostore.cl" -> slug "demo") that
//      every store keeps working under regardless — internal access/testing before a client's
//      domain is configured, or for stores that never set one. Locally, "demo.localhost:8080"
//      resolves the same way without any hosts-file changes, since *.localhost already points at
//      127.0.0.1 in modern browsers/OS resolvers.
//
// Leaves TenantContext empty (not an error) when neither resolves — routes that don't need a
// tenant this way (POS sync/webhooks, which resolve their store via companyId instead;
// actuator/health; swagger; platform onboarding, which creates the store) simply never read
// TenantContext, so they're unaffected.
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
            String host = request.getHeader("Host");
            String hostname = hostnameOnly(host);
            if (hostname != null) {
                storeRepository.findByDomain(hostname)
                        .or(() -> {
                            String slug = extractSlug(hostname);
                            return slug == null ? Optional.empty() : storeRepository.findBySlug(slug);
                        })
                        .ifPresent(TenantContext::set);
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String hostnameOnly(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        return host.split(":")[0];
    }

    private String extractSlug(String hostname) {
        int firstDot = hostname.indexOf('.');
        if (firstDot <= 0) {
            return null; // no subdomain: bare "localhost", an IP, etc.
        }
        return hostname.substring(0, firstDot);
    }
}
