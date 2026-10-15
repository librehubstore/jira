package com.fluxo.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GroupRepository extends JpaRepository<Group, UUID> {

    boolean existsByNameIgnoreCase(String name);

    List<Group> findAllByOrderByNameAsc();
}
