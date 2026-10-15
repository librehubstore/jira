package com.fluxo.ticket;

import com.fluxo.space.Space;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Space version (spec §4.13): unreleased, released or archived. */
@Entity
@Table(name = "versions")
public class FixVersion {

    public enum Status { UNRELEASED, RELEASED, ARCHIVED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Space space;

    @Column(nullable = false)
    private String name;

    private String description;

    private LocalDate startDate;

    private LocalDate releaseDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.UNRELEASED;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected FixVersion() {
    }

    public FixVersion(Space space, String name) {
        this.space = space;
        this.name = name;
    }

    public UUID getId() { return id; }
    public Space getSpace() { return space; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getReleaseDate() { return releaseDate; }
    public void setReleaseDate(LocalDate releaseDate) { this.releaseDate = releaseDate; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
