package org.uvo.uvostore.service.platform;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.entity.tenant.Store;
import org.uvo.uvostore.repository.StoreRepository;
import org.uvo.uvostore.repository.UserRepository;

import java.util.NoSuchElementException;

// Store onboarding is deliberately NOT self-service — see PlatformApiKeyAuthFilter. The operator
// team collects the client's nick/domain/admin credentials through whatever channel they use, and
// enters them here once; from that point on the client manages everything themselves through the
// normal admin panel (already fully built) under their own slug/domain.
@Service
public class StoreOnboardingServiceImpl implements StoreOnboardingService {

    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public StoreOnboardingServiceImpl(StoreRepository storeRepository, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public StoreOnboardingResponse createStore(StoreOnboardingCommand command) {
        String slug = command.slug().toLowerCase().trim();
        if (storeRepository.existsBySlug(slug)) {
            throw new IllegalStateException("Ese nick ya está en uso");
        }
        String domain = normalizeDomain(command.domain());
        if (domain != null && storeRepository.existsByDomain(domain)) {
            throw new IllegalStateException("Ese dominio ya está en uso");
        }

        Store store = new Store();
        store.setName(command.storeName());
        store.setSlug(slug);
        store.setDomain(domain);
        store.setStatus("active");
        Store savedStore = storeRepository.save(store);

        User admin = new User();
        admin.setStore(savedStore);
        admin.setName(command.adminName());
        admin.setEmail(command.adminEmail());
        admin.setPassword(passwordEncoder.encode(command.adminPassword()));
        admin.setActive(true);
        admin.setAdmin(true);
        User savedAdmin = userRepository.save(admin);

        savedStore.setOwnerUserId(savedAdmin.getId());
        storeRepository.save(savedStore);

        return toResponse(savedStore, savedAdmin);
    }

    @Override
    @Transactional
    public StoreOnboardingResponse updateDomain(Long storeId, String domain) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NoSuchElementException("Store " + storeId + " not found"));

        String normalized = normalizeDomain(domain);
        if (normalized != null && !normalized.equals(store.getDomain()) && storeRepository.existsByDomain(normalized)) {
            throw new IllegalStateException("Ese dominio ya está en uso");
        }

        store.setDomain(normalized);
        Store saved = storeRepository.save(store);
        User admin = saved.getOwnerUserId() == null ? null : userRepository.findById(saved.getOwnerUserId()).orElse(null);
        return toResponse(saved, admin);
    }

    private String normalizeDomain(String domain) {
        return domain == null || domain.isBlank() ? null : domain.toLowerCase().trim();
    }

    private StoreOnboardingResponse toResponse(Store store, User admin) {
        return new StoreOnboardingResponse(
                store.getId(), store.getName(), store.getSlug(), store.getDomain(),
                admin == null ? null : admin.getId(), admin == null ? null : admin.getEmail()
        );
    }
}
