package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uvo.uvostore.entity.pos.PosConnectionKey;

import java.util.List;
import java.util.Optional;

public interface PosConnectionKeyRepository extends JpaRepository<PosConnectionKey, Long> {

    /** La llave con ese id en esa dirección, con su conexión y su tienda: el filtro de entrada las necesita. */
    @Query("""
            select k from PosConnectionKey k join fetch k.connection c left join fetch c.store
            where k.direction = :direction and k.keyId = :keyId
            """)
    Optional<PosConnectionKey> findWithConnection(@Param("direction") PosConnectionKey.Direction direction,
                                                  @Param("keyId") String keyId);

    /** Las llaves de una conexión en una dirección, la más nueva primero. */
    @Query("""
            select k from PosConnectionKey k
            where k.connection.id = :connectionId and k.direction = :direction
            order by k.createdAt desc, k.id desc
            """)
    List<PosConnectionKey> findByConnection(@Param("connectionId") Long connectionId,
                                            @Param("direction") PosConnectionKey.Direction direction);
}
