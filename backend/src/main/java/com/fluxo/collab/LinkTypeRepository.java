package com.fluxo.collab;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LinkTypeRepository extends JpaRepository<Links.LinkType, UUID> {

    List<Links.LinkType> findAllByOrderByNameAsc();
}
