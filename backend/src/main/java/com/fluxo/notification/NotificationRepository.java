package com.fluxo.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("""
            select n from Notification n left join fetch n.actor
            where n.user.id = :userId and (:unreadOnly = false or n.readAt is null)
            order by n.updatedAt desc
            """)
    List<Notification> findForUser(UUID userId, boolean unreadOnly, Pageable page);

    long countByUserIdAndReadAtIsNull(UUID userId);

    /** Candidate for grouping: same recipient, ticket, actor and event, still unread, updated recently. */
    @Query("""
            select n from Notification n
            where n.user.id = :userId and n.ticket.id = :ticketId and n.actor.id = :actorId and n.event = :event
              and n.readAt is null and n.updatedAt >= :since
            order by n.updatedAt desc limit 1
            """)
    Optional<Notification> findGroupable(UUID userId, UUID ticketId, UUID actorId, String event, Instant since);

    @Modifying
    @Query("update Notification n set n.readAt = :at where n.user.id = :userId and n.readAt is null")
    int markAllRead(UUID userId, Instant at);
}
