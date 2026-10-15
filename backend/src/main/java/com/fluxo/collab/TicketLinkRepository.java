package com.fluxo.collab;

import org.springframework.data.jpa.repository.JpaRepository;
import com.fluxo.workflow.StatusCategory;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TicketLinkRepository extends JpaRepository<Links.TicketLink, UUID> {

    @Query("""
            select l from TicketLink l join fetch l.linkType
            join fetch l.source s join fetch s.space join fetch s.status join fetch s.type join fetch s.priority
            join fetch l.target t join fetch t.space join fetch t.status join fetch t.type join fetch t.priority
            where s.id = :ticketId or t.id = :ticketId
            """)
    List<Links.TicketLink> findForTicket(UUID ticketId);

    boolean existsBySourceIdAndTargetIdAndLinkTypeId(UUID sourceId, UUID targetId, UUID linkTypeId);

    /** Tickets among the given ids that are blocked by a ticket not yet done (card indicator, spec §4.10). */
    @Query("""
            select distinct t.id from TicketLink l join l.target t join l.source s join l.linkType lt
            where lt.name = 'Blocks' and t.id in :ids and s.status.category <> :done
            """)
    List<UUID> blockedAmong(Collection<UUID> ids, StatusCategory done);

    default List<UUID> blockedAmong(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : blockedAmong(ids, StatusCategory.DONE);
    }
}
