package com.fluxo.collab;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    @Query("select c from Comment c join fetch c.author where c.ticket.id = :ticketId order by c.createdAt, c.id")
    List<Comment> findByTicket(UUID ticketId);
}
