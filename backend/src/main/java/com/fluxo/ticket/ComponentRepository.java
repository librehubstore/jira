package com.fluxo.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ComponentRepository extends JpaRepository<Component, UUID> {
    List<Component> findBySpaceIdOrderByNameAsc(UUID spaceId);

    @Query("select c from Component c where c.space.id = :spaceId and lower(c.name) = lower(:name)")
    Optional<Component> findByName(UUID spaceId, String name);
}
