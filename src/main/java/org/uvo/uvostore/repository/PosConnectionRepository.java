package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.uvostore.entity.pos.PosConnection;

import java.util.List;
import java.util.Optional;

public interface PosConnectionRepository extends JpaRepository<PosConnection, Long> {

    Optional<PosConnection> findByCompanyId(Long companyId);
    List<PosConnection> findByIsActiveTrue();
}
