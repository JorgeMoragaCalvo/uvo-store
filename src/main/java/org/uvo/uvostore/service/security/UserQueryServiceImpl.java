package org.uvo.uvostore.service.security;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.security.User;
import org.uvo.uvostore.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class UserQueryServiceImpl implements UserQueryService {

    private final UserRepository userRepository;

    public UserQueryServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserDto> search(String search, Long roleId, Boolean active, Pageable pageable) {
        List<Specification<User>> specs = new ArrayList<>();

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
                .orElseThrow(() -> new NoSuchElementException("User " + id + " not found"));
        return toDto(user);
    }

    private UserDto toDto(User u) {
        List<RoleRefDto> roles = u.getRoles().stream()
                .map(r -> new RoleRefDto(r.getId(), r.getName()))
                .toList();
        return new UserDto(u.getId(), u.getName(), u.getEmail(), u.getPhone(), u.getAvatar(), u.isActive(),
                u.getLastLoginAt(), u.getNotes(), roles, u.getCreatedAt());
    }
}
