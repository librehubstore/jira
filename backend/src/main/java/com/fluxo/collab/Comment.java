package com.fluxo.collab;

import com.fluxo.ticket.Ticket;
import com.fluxo.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User author;

    /** Markdown, like descriptions. */
    @Column(nullable = false)
    private String body;

    private Instant editedAt;

    /** Only members of this space role (and the author, space admins) see the comment; null = everyone (spec §4.4). */
    @Column(name = "visibility_role_id")
    private UUID visibilityRoleId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Comment() {
    }

    public Comment(Ticket ticket, User author, String body) {
        this.ticket = ticket;
        this.author = author;
        this.body = body;
    }

    public UUID getId() { return id; }
    public Ticket getTicket() { return ticket; }
    public User getAuthor() { return author; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Instant getEditedAt() { return editedAt; }
    public UUID getVisibilityRoleId() { return visibilityRoleId; }
    public void setVisibilityRoleId(UUID visibilityRoleId) { this.visibilityRoleId = visibilityRoleId; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
