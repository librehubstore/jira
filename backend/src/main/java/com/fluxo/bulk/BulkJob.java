package com.fluxo.bulk;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A bulk change running in the background (spec §4.12); progress and report are read by polling. */
@Entity
@Table(name = "bulk_jobs")
public class BulkJob {

    public enum Operation { EDIT, TRANSITION, MOVE, DELETE, WATCH, UNWATCH }

    public record Failure(String key, String reason) {}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Operation operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> params;

    @Column(nullable = false)
    private int total;

    @Column(nullable = false)
    private int done;

    @Column(nullable = false)
    private int failed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Failure> report = new ArrayList<>();

    @Column(nullable = false)
    private String status = "RUNNING";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant finishedAt;

    protected BulkJob() {
    }

    BulkJob(UUID userId, Operation operation, Map<String, Object> params, int total) {
        this.userId = userId;
        this.operation = operation;
        this.params = params;
        this.total = total;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Operation getOperation() { return operation; }
    public Map<String, Object> getParams() { return params; }
    public int getTotal() { return total; }
    public int getDone() { return done; }
    public int getFailed() { return failed; }
    public List<Failure> getReport() { return report; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
