package org.uvo.uvostore.security;

import org.springframework.stereotype.Component;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.StoreRepository;

import java.util.Optional;

// The one place that turns a hostname into a Store, in two steps:
//   1. Exact match of the whole hostname against Store.domain — a client's own custom domain
//      (e.g. "tiendadejuan.cl"), the primary path once a client's DNS is pointed at us.
//   2. Falling back to the subdomain-slug scheme (e.g. "demo.uvostore.cl" -> slug "demo") that
//      every store keeps working under regardless. Locally, "demo.localhost:8080" resolves the
//      same way without any hosts-file changes, since *.localhost already points at 127.0.0.1.
//
// Extracted from TenantResolutionFilter because the CORS allowlist (A2) needs exactly the same
// resolution, but against the Origin header rather than Host, and at a point in the request where
// TenantContext isn't populated yet: a CORS preflight is answered before the security filter chain
// runs, so it can't reuse the filter's result and must not duplicate its logic either.
@Component
public class StoreHostResolver {

    private final StoreRepository storeRepository;

    public StoreHostResolver(StoreRepository storeRepository) {
        this.storeRepository = storeRepository;
    }

    public Optional<Store> resolve(String hostHeaderOrHostname) {
        String hostname = hostnameOnly(hostHeaderOrHostname);
        if (hostname == null) {
            return Optional.empty();
        }
        return storeRepository.findByDomain(hostname)
                .or(() -> {
                    String slug = extractSlug(hostname);
                    return slug == null ? Optional.empty() : storeRepository.findBySlug(slug);
                });
    }

    public String hostnameOnly(String host) {
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
