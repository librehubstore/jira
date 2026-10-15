package com.fluxo.search;

import com.fluxo.space.Space;
import com.fluxo.user.Group;
import com.fluxo.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A named FQL query, private or shared with a group, a space or everyone (spec §4.11). */
@Entity
@Table(name = "saved_filters")
public class SavedFilter {

    public enum Scope { PRIVATE, GROUP, SPACE, ALL }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String fql;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> columns = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Scope shareScope = Scope.PRIVATE;

    @ManyToOne(fetch = FetchType.LAZY)
    private Group shareGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    private Space shareSpace;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected SavedFilter() {
    }

    public SavedFilter(User owner, String name, String fql) {
        this.owner = owner;
        this.name = name;
        this.fql = fql;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public User getOwner() { return owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFql() { return fql; }
    public void setFql(String fql) { this.fql = fql; }
    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }
    public Scope getShareScope() { return shareScope; }
    public void setShareScope(Scope shareScope) { this.shareScope = shareScope; }
    public Group getShareGroup() { return shareGroup; }
    public void setShareGroup(Group shareGroup) { this.shareGroup = shareGroup; }
    public Space getShareSpace() { return shareSpace; }
    public void setShareSpace(Space shareSpace) { this.shareSpace = shareSpace; }
}
