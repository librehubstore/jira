package com.fluxo.common;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Append-only log of administrative actions (spec §10). */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID actorId;

    @Column(nullable = false)
    private String action;

    private String target;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> details;

    @Column(nullable = false)
    private Instant at = Instant.now();

    protected AuditLog() {
    }

    public AuditLog(UUID actorId, String action, String target, Map<String, Object> details) {
        this.actorId = actorId;
        this.action = action;
        this.target = target;
        this.details = details;
    }

    public String getAction() { return action; }
    public String getTarget() { return target; }
}
