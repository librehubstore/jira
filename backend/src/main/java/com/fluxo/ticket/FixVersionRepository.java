package com.fluxo.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FixVersionRepository extends JpaRepository<FixVersion, UUID> {
    @Query("select v from FixVersion v where v.space.id = :spaceId order by coalesce(v.releaseDate, v.startDate), v.createdAt")
    List<FixVersion> findForSpace(UUID spaceId);

    @Query("select v from FixVersion v where v.space.id = :spaceId and lower(v.name) = lower(:name)")
    Optional<FixVersion> findByName(UUID spaceId, String name);
}
