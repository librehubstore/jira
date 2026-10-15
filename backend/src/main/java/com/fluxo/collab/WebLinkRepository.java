package com.fluxo.collab;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebLinkRepository extends JpaRepository<Links.WebLink, UUID> {

    List<Links.WebLink> findByTicketIdOrderByTitleAsc(UUID ticketId);
}
