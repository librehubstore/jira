package com.fluxo.collab;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    @Query("select a from Attachment a join fetch a.uploader where a.ticket.id = :ticketId order by a.createdAt")
    List<Attachment> findByTicket(UUID ticketId);
}
