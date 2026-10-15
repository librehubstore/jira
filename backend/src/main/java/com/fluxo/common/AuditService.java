package com.fluxo.common;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository repo;

    AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void log(UUID actorId, String action, String target) {
        log(actorId, action, target, Map.of());
    }

    public void log(UUID actorId, String action, String target, Map<String, Object> details) {
        repo.save(new AuditLog(actorId, action, target, details));
    }
}
