package com.fluxo.space;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpaceRoleRepository extends JpaRepository<SpaceRole, UUID> {

    List<SpaceRole> findBySpaceIdOrderByNameAsc(UUID spaceId);
}
