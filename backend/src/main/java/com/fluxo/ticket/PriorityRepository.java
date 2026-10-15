package com.fluxo.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriorityRepository extends JpaRepository<RefData.Priority, UUID> {

    List<RefData.Priority> findAllByOrderByRankAsc();

    Optional<RefData.Priority> findFirstByIsDefaultTrue();

    Optional<RefData.Priority> findByNameIgnoreCase(String name);
}
