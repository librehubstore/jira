package com.fluxo.ticket;

import com.fluxo.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** Append-only change log of a ticket (spec §4.8). Raw values for machines, display values for humans. */
@Entity
@Immutable
@Table(name = "history_entries")
public class HistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User actor;

    @Column(nullable = false)
    private Instant at = Instant.now();

    @Column(nullable = false)
    private String field;

    private String oldValue;
    private String newValue;
    private String oldDisplay;
    private String newDisplay;

    protected HistoryEntry() {
    }

    public HistoryEntry(Ticket ticket, User actor, String field, String oldValue, String newValue,
                        String oldDisplay, String newDisplay) {
        this.ticket = ticket;
        this.actor = actor;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.oldDisplay = oldDisplay;
        this.newDisplay = newDisplay;
    }

    public UUID getId() { return id; }
    public Ticket getTicket() { return ticket; }
    public User getActor() { return actor; }
    public Instant getAt() { return at; }
    public String getField() { return field; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public String getOldDisplay() { return oldDisplay; }
    public String getNewDisplay() { return newDisplay; }
}
