package com.fluxo.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabelRepository extends JpaRepository<Label, UUID> {

    @Query("select l from Label l where l.space.id = :spaceId and lower(l.name) = lower(:name)")
    Optional<Label> findByName(UUID spaceId, String name);

    @Query("select l from Label l where l.space.id = :spaceId and lower(l.name) like lower(concat(:prefix, '%')) order by l.name")
    List<Label> search(UUID spaceId, String prefix);
}
