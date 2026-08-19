package org.uvo.uvostore.service.security;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserQueryService {
    Page<UserDto> search(String search, Long roleId, Boolean active, Pageable pageable);
    UserDto getById(Long id);
}
