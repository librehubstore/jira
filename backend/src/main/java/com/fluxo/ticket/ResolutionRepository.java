package com.fluxo.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResolutionRepository extends JpaRepository<RefData.Resolution, UUID> {

    List<RefData.Resolution> findAllByOrderByNameAsc();

    Optional<RefData.Resolution> findFirstByIsDefaultTrue();
}
