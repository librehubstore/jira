package com.fluxo.space;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpaceRepository extends JpaRepository<Space, UUID> {

    Optional<Space> findByKey(String key);

    boolean existsByKey(String key);

    @Query("select a.space from SpaceKeyAlias a where a.oldKey = :key")
    Optional<Space> findByAlias(String key);

    List<Space> findByIdInOrderByNameAsc(Collection<UUID> ids);

    List<Space> findAllByOrderByNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Space s where s.id = :id")
    Optional<Space> lockById(UUID id);
}
