package org.uvo.uvostore.service.security;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.repository.UserRepository;
import org.uvo.uvostore.security.TenantContext;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class UserQueryServiceImpl implements UserQueryService {

    private final UserRepository userRepository;
    private final AdminInvitationPolicy invitationPolicy;

    public UserQueryServiceImpl(UserRepository userRepository, AdminInvitationPolicy invitationPolicy) {
        this.userRepository = userRepository;
        this.invitationPolicy = invitationPolicy;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserDto> search(String search, Long roleId, Boolean active, Pageable pageable) {
        List<Specification<User>> specs = new ArrayList<>();
        Long storeId = TenantContext.requireStoreId();
        specs.add((root, query, cb) -> cb.equal(root.get("store").get("id"), storeId));

        if (search != null && !search.isBlank()) {
            String term = "%" + search.toLowerCase() + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), term),
                    cb.like(cb.lower(root.get("email")), term),
                    cb.like(cb.lower(root.get("phone")), term)
            ));
        }
        if (roleId != null) {
            specs.add((root, query, cb) -> {
                query.distinct(true);
                return cb.equal(root.join("roles").get("id"), roleId);
            });
        }
        if (active != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("active"), active));
        }

        Specification<User> spec = specs.stream().reduce(Specification::and).orElse(null);
        return userRepository.findAll(spec, pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDto getById(Long id) {
        User user = userRepository.findById(id)
                .filter(u -> u.getStore().getId().equals(TenantContext.requireStoreId()))
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));
        return toDto(user);
    }

    private UserDto toDto(User u) {
        List<RoleRefDto> roles = u.getRoles().stream()
                .map(r -> new RoleRefDto(r.getId(), r.getName()))
                .toList();
        // PROD-06: pendiente es "hay token y nadie lo ha usado". Se comprueba también
        // `invitationAcceptedAt` y no solo el token porque son dos preguntas distintas y conviene que una
        // fila a medias (token presente y aceptación escrita, que no debería ocurrir) no se lea como
        // pendiente.
        boolean invitationPending = u.getInvitationToken() != null && u.getInvitationAcceptedAt() == null;
        return new UserDto(u.getId(), u.getName(), u.getEmail(), u.getPhone(), u.getAvatar(), u.isActive(),
                u.getLastLoginAt(), u.getNotes(), roles, u.getCreatedAt(),
                // La fecha de caducidad solo tiene sentido mientras haya invitación viva: en una cuenta
                // normal, `invitation_sent_at` es un dato histórico que no caduca de nada.
                invitationPending, invitationPending ? invitationPolicy.expiresAt(u) : null);
    }
}
