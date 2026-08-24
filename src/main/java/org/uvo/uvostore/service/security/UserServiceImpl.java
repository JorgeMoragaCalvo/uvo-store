package org.uvo.uvostore.service.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.security.Role;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.repository.RoleRepository;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.TenantContext;
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

    public UserServiceImpl(UserRepository userRepository, RoleRepository roleRepository,
                            PasswordEncoder passwordEncoder, FileStorageService fileStorageService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.fileStorageService = fileStorageService;
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
        if (command.roleIds() != null) {
            user.setRoles(resolveRoles(command.roleIds()));
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
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void assignRoles(Long userId, Set<Long> roleIds) {
        User user = userRepository.findById(userId)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + userId + " not found"));
        user.setRoles(resolveRoles(roleIds));
        userRepository.save(user);
    }

    @Override
    @Transactional
    public User toggleActive(Long id) {
        User user = userRepository.findById(id)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));
        user.setActive(!user.isActive());
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));
        userRepository.delete(user);
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
