package com.fluxo.space;

import com.fluxo.ticket.TicketType;
import com.fluxo.user.User;
import com.fluxo.workflow.Workflow;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "spaces")
public class Space {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String key;

    @Column(nullable = false)
    private String name;

    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    private User lead;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Workflow workflow;

    @Column(nullable = false)
    private boolean archived;

    /** Next ticket number; taken under a row lock, never reused. */
    @Column(nullable = false)
    private int nextNumber = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> settings = new HashMap<>();

    @ManyToMany
    @JoinTable(name = "space_ticket_types",
            joinColumns = @JoinColumn(name = "space_id"),
            inverseJoinColumns = @JoinColumn(name = "ticket_type_id"))
    private Set<TicketType> ticketTypes = new LinkedHashSet<>();

    /** Workflow per ticket type; other types use {@link #workflow} (spec §4.9). */
    @ManyToMany
    @JoinTable(name = "space_type_workflows", joinColumns = @JoinColumn(name = "space_id"),
            inverseJoinColumns = @JoinColumn(name = "workflow_id"))
    @MapKeyJoinColumn(name = "type_id")
    private Map<TicketType, Workflow> typeWorkflows = new HashMap<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected Space() {
    }

    public Space(String key, String name, Workflow workflow) {
        this.key = key;
        this.name = name;
        this.workflow = workflow;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public int takeNextNumber() {
        return nextNumber++;
    }

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public User getLead() { return lead; }
    public void setLead(User lead) { this.lead = lead; }
    public Workflow getWorkflow() { return workflow; }
    public Workflow workflowFor(TicketType type) { return typeWorkflows.getOrDefault(type, workflow); }
    public Map<TicketType, Workflow> getTypeWorkflows() { return typeWorkflows; }
    /** The space workflow first, then the per-type ones. */
    public List<Workflow> allWorkflows() {
        List<Workflow> all = new java.util.ArrayList<>(List.of(workflow));
        typeWorkflows.values().stream().filter(w -> !all.contains(w)).forEach(all::add);
        return all;
    }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public Map<String, Object> getSettings() { return settings; }
    public void setSettings(Map<String, Object> settings) { this.settings = settings; }
    public Set<TicketType> getTicketTypes() { return ticketTypes; }
    public Instant getCreatedAt() { return createdAt; }
}
