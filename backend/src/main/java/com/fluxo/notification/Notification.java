package com.fluxo.notification;

import com.fluxo.ticket.Ticket;
import com.fluxo.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** In-app notification (spec §9). Payload holds what the bell shows: key, summary, changes, excerpt. */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    private User actor;

    /** Null once the ticket is deleted (the payload keeps its key and summary). */
    @ManyToOne(fetch = FetchType.LAZY)
    private Ticket ticket;

    @Column(nullable = false)
    private String event;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload = new HashMap<>();

    private Instant readAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected Notification() {
    }

    public Notification(User user, User actor, Ticket ticket, String event, Map<String, Object> payload) {
        this.user = user;
        this.actor = actor;
        this.ticket = ticket;
        this.event = event;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public User getActor() { return actor; }
    public Ticket getTicket() { return ticket; }
    public String getEvent() { return event; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
