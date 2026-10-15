package com.fluxo.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

    @Query("select t from Ticket t join fetch t.space s where s.key = :spaceKey and t.number = :number")
    Optional<Ticket> findByKey(String spaceKey, int number);

    @Query(value = "select ticket_id from ticket_key_aliases where old_key = :key", nativeQuery = true)
    Optional<UUID> findIdByAlias(String key);

    @Modifying
    @Query(value = "insert into ticket_key_aliases (old_key, ticket_id) values (:key, :ticketId) on conflict (old_key) do update set ticket_id = :ticketId", nativeQuery = true)
    void saveAlias(String key, UUID ticketId);

    @Query("select max(t.rank) from Ticket t where t.space.id = :spaceId")
    Optional<String> maxRank(UUID spaceId);

    @Query("select t from Ticket t join fetch t.status join fetch t.type where t.parent.id = :parentId order by t.rank")
    List<Ticket> findChildren(UUID parentId);

    boolean existsByParentIdAndTypeHierarchyLevel(UUID parentId, short level);

    /** All tickets of the board's statuses, with what a card shows (spec §4.10). */
    @Query("""
            select t from Ticket t join fetch t.status join fetch t.type join fetch t.priority join fetch t.space
            left join fetch t.assignee join fetch t.reporter left join fetch t.parent p left join fetch p.space
            where t.space.id in :spaceIds and t.status.id in :statusIds and t.archived = false
              and (t.resolvedAt is null or t.resolvedAt >= :doneSince)
            order by t.rank
            """)
    List<Ticket> findForBoard(java.util.Collection<UUID> spaceIds, java.util.Collection<UUID> statusIds, java.time.Instant doneSince);

    @Query("select count(t) from Ticket t where t.space.id in :spaceIds and t.status.id not in :mappedIds")
    long countUnmapped(java.util.Collection<UUID> spaceIds, java.util.Collection<UUID> mappedIds);

    @Query("select t.rank from Ticket t where t.space.id = :spaceId and t.number = :number")
    Optional<String> rankOf(UUID spaceId, int number);
}
