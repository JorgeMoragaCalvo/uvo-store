package org.uvo.uvostore.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.repository.CustomerRepository;
import org.uvo.uvostore.repository.UserRepository;

import java.time.Duration;

// A5: the read and write side of JWT revocation.
//
// JwtAuthenticationFilter asks this on every authenticated request, so the lookup is cached rather
// than hitting the database each time. Revocation still takes effect immediately, because bumping
// a version evicts that principal's entry in the same call — the TTL only matters for a second
// process that didn't perform the bump.
//
// Known limitation, worth stating plainly: across multiple instances, revocation lags by up to the
// cache TTL. There is no deployment infrastructure yet (a single process), so today it is
// immediate. Moving to more than one instance means moving this to a shared store.
@Service
public class TokenVersionService {

    public static final String ADMIN = "ADMIN";
    public static final String CUSTOMER = "CUSTOMER";

    private static final Duration TTL = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final Cache<String, Integer> versions = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(TTL)
            .build();

    public TokenVersionService(UserRepository userRepository, CustomerRepository customerRepository) {
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
    }

    /**
     * Current version for a principal, or -1 when it no longer exists — a deleted account's tokens
     * can then never match, which is the point.
     */
    public int currentVersion(String principalType, Long principalId) {
        return versions.get(cacheKey(principalType, principalId), key -> CUSTOMER.equals(principalType)
                ? customerRepository.findById(principalId).map(c -> c.getTokenVersion()).orElse(-1)
                : userRepository.findById(principalId).map(u -> u.getTokenVersion()).orElse(-1));
    }

    /** Invalidates every token already issued to this admin user. */
    @Transactional
    public void revokeUserTokens(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setTokenVersion(user.getTokenVersion() + 1);
            userRepository.save(user);
            versions.invalidate(cacheKey(ADMIN, userId));
        });
    }

    /** Invalidates every token already issued to this customer. */
    @Transactional
    public void revokeCustomerTokens(Long customerId) {
        customerRepository.findById(customerId).ifPresent(customer -> {
            customer.setTokenVersion(customer.getTokenVersion() + 1);
            customerRepository.save(customer);
            versions.invalidate(cacheKey(CUSTOMER, customerId));
        });
    }

    /** Drops a cached entry without changing anything — used right after issuing a fresh token. */
    public void evict(String principalType, Long principalId) {
        versions.invalidate(cacheKey(principalType, principalId));
    }

    private String cacheKey(String principalType, Long principalId) {
        return principalType + ":" + principalId;
    }
}
