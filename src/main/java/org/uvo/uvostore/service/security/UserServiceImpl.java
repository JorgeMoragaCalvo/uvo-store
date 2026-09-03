package org.uvo.uvostore.service.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.TenantContext;
import org.uvo.uvostore.security.TokenVersionService;
import org.uvo.uvostore.service.catalog.FileStorageService;

import java.time.Instant;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;
    private final TokenVersionService tokenVersionService;

    public UserServiceImpl(UserRepository userRepository, RoleRepository roleRepository,
                            PasswordEncoder passwordEncoder, FileStorageService fileStorageService,
                            TokenVersionService tokenVersionService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.fileStorageService = fileStorageService;
        this.tokenVersionService = tokenVersionService;
    }

    @Override
    @Transactional
    public User createUser(UserCreateCommand command) {
        User user = new User();
        user.setStore(TenantContext.requireCurrent());
        applyCommonFields(user, command);
        user.setPassword(passwordEncoder.encode(command.password()));
        if (command.sendInvitation()) {
            user.setInvitationToken(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
            user.setInvitationSentAt(Instant.now());
        }
        if (command.avatar() != null && !command.avatar().isEmpty()) {
            user.setAvatar(fileStorageService.store(command.avatar(), "avatars"));
        }
        User saved = userRepository.save(user);
        if (command.roleIds() != null) {
            saved.setRoles(resolveRoles(command.roleIds()));
        }
        return userRepository.save(saved);
    }

    @Override
    @Transactional
    public User updateUser(Long id, UserCreateCommand command) {
        User user = userRepository.findById(id)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));
        applyCommonFields(user, command);
        if (command.password() != null && !command.password().isBlank()) {
            user.setPassword(passwordEncoder.encode(command.password()));
        }
        if (command.avatar() != null && !command.avatar().isEmpty()) {
            if (user.getAvatar() != null) {
                fileStorageService.delete(user.getAvatar());
            }
            user.setAvatar(fileStorageService.store(command.avatar(), "avatars"));
        }
        // A5: a changed password or a changed role set has to invalidate sessions already open —
        // permissions are frozen into the JWT at login, so without this a demoted admin keeps the
        // old ones until the token expires. A plain profile edit deliberately doesn't revoke:
        // logging someone out for renaming themselves would be gratuitous.
        boolean credentialsOrRolesChanged = command.password() != null && !command.password().isBlank();
        if (command.roleIds() != null) {
            user.setRoles(resolveRoles(command.roleIds()));
            credentialsOrRolesChanged = true;
        }
        if (credentialsOrRolesChanged) {
            revokeTokens(user);
        }
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public void deactivateUser(Long id) {
        User user = userRepository.findById(id)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));
        user.setActive(false);
        revokeTokens(user);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void assignRoles(Long userId, Set<Long> roleIds) {
        User user = userRepository.findById(userId)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + userId + " not found"));
        user.setRoles(resolveRoles(roleIds));
        revokeTokens(user);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public User toggleActive(Long id) {
        User user = userRepository.findById(id)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));
        user.setActive(!user.isActive());
        revokeTokens(user);
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));
        userRepository.delete(user);
        // No version to bump — the row is gone, so currentVersion() answers -1 and nothing matches.
        // The eviction is what matters: a cached version would otherwise keep a deleted admin's
        // token working until the entry expired.
        tokenVersionService.evict(TokenVersionService.ADMIN, id);
    }

    // Bumps in memory so the caller's own save() persists it in the same transaction, and drops the
    // cached value so revocation takes effect on the very next request.
    private void revokeTokens(User user) {
        user.setTokenVersion(user.getTokenVersion() + 1);
        tokenVersionService.evict(TokenVersionService.ADMIN, user.getId());
    }

    private void applyCommonFields(User user, UserCreateCommand command) {
        user.setName(command.name());
        user.setEmail(command.email());
        user.setPhone(command.phone());
        user.setAdmin(command.isAdmin());
        user.setActive(command.active());
        user.setNotes(command.notes());
    }

    private Set<Role> resolveRoles(Set<Long> roleIds) {
        Long storeId = TenantContext.requireStoreId();
        Set<Role> roles = new HashSet<>();
        for (Long roleId : roleIds) {
            roles.add(roleRepository.findById(roleId)
                    .filter(r -> r.getStore().getId().equals(storeId))
                    .orElseThrow(() -> new NoSuchElementException("Role " + roleId + " not found")));
        }
        return roles;
    }
}
