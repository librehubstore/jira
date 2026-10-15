package com.fluxo.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface HistoryRepository extends JpaRepository<HistoryEntry, UUID> {

    @Query("select h from HistoryEntry h join fetch h.actor where h.ticket.id = :ticketId order by h.at, h.id")
    List<HistoryEntry> findByTicket(UUID ticketId);
}
