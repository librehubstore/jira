package com.fluxo.ticket;

import com.fluxo.space.Space;
import com.fluxo.user.User;
import com.fluxo.workflow.Status;
import com.fluxo.workflow.StatusCategory;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Space space;

    @Column(nullable = false)
    private int number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private TicketType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Status status;

    @ManyToOne(fetch = FetchType.LAZY)
    private RefData.Resolution resolution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RefData.Priority priority;

    @Column(nullable = false)
    private String summary;

    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    private User assignee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User reporter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY)
    private Ticket parent;

    private LocalDate dueDate;

    private LocalDate startDate;

    private BigDecimal estimate;

    @Column(nullable = false)
    private String rank;

    @Column(nullable = false)
    private boolean flagged;

    private Instant resolvedAt;

    @Version
    private int version;

    @ManyToMany
    @JoinTable(name = "ticket_labels",
            joinColumns = @JoinColumn(name = "ticket_id"),
            inverseJoinColumns = @JoinColumn(name = "label_id"))
    private Set<Label> labels = new LinkedHashSet<>();

    /** People working on the ticket besides the assignee (ADD_FUNCTIONALITIES A.1). */
    @ManyToMany
    @JoinTable(name = "ticket_collaborators",
            joinColumns = @JoinColumn(name = "ticket_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> collaborators = new LinkedHashSet<>();

    /** Archived: off the board and out of default searches, still readable (A.2). */
    @Column(nullable = false)
    private boolean archived;

    @ManyToMany
    @JoinTable(name = "ticket_components",
            joinColumns = @JoinColumn(name = "ticket_id"),
            inverseJoinColumns = @JoinColumn(name = "component_id"))
    private Set<Component> components = new LinkedHashSet<>();

    @ManyToMany
    @JoinTable(name = "ticket_fix_versions",
            joinColumns = @JoinColumn(name = "ticket_id"),
            inverseJoinColumns = @JoinColumn(name = "version_id"))
    private Set<FixVersion> fixVersions = new LinkedHashSet<>();

    /** Custom field values by field id (spec §4.14). */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> customValues = new HashMap<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected Ticket() {
    }

    public Ticket(Space space, int number, TicketType type, Status status, RefData.Priority priority, String summary,
                  User reporter, User creator, String rank) {
        this.space = space;
        this.number = number;
        this.type = type;
        this.status = status;
        this.priority = priority;
        this.summary = summary;
        this.reporter = reporter;
        this.creator = creator;
        this.rank = rank;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public String getKey() {
        return space.getKey() + "-" + number;
    }

    public boolean isDone() {
        return status.getCategory() == StatusCategory.DONE;
    }

    public UUID getId() { return id; }
    public Space getSpace() { return space; }
    public void setSpace(Space space) { this.space = space; }
    public int getNumber() { return number; }
    public void setNumber(int number) { this.number = number; }
    public TicketType getType() { return type; }
    public void setType(TicketType type) { this.type = type; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public RefData.Resolution getResolution() { return resolution; }
    public void setResolution(RefData.Resolution resolution) { this.resolution = resolution; }
    public RefData.Priority getPriority() { return priority; }
    public void setPriority(RefData.Priority priority) { this.priority = priority; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public User getAssignee() { return assignee; }
    public void setAssignee(User assignee) { this.assignee = assignee; }
    public User getReporter() { return reporter; }
    public void setReporter(User reporter) { this.reporter = reporter; }
    public User getCreator() { return creator; }
    public Ticket getParent() { return parent; }
    public void setParent(Ticket parent) { this.parent = parent; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public BigDecimal getEstimate() { return estimate; }
    public void setEstimate(BigDecimal estimate) { this.estimate = estimate; }
    public String getRank() { return rank; }
    public void setRank(String rank) { this.rank = rank; }
    public boolean isFlagged() { return flagged; }
    public void setFlagged(boolean flagged) { this.flagged = flagged; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public int getVersion() { return version; }
    public Set<Label> getLabels() { return labels; }
    public Set<Component> getComponents() { return components; }
    public Set<User> getCollaborators() { return collaborators; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public Map<String, Object> getCustomValues() { return customValues; }
    public void setCustomValues(Map<String, Object> customValues) { this.customValues = customValues; }
    public Set<FixVersion> getFixVersions() { return fixVersions; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
