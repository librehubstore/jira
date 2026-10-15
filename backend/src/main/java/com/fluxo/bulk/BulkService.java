package com.fluxo.bulk;

import com.fluxo.auth.AuthUser;
import com.fluxo.collab.WatcherService;
import com.fluxo.common.Errors;
import com.fluxo.notification.NotificationService;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.ticket.Label;
import com.fluxo.ticket.Ticket;
import com.fluxo.ticket.TicketService;
import com.fluxo.workflow.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bulk changes (spec §4.12): one transaction per ticket in a background thread, so one refusal never blocks the rest. */
@Service
public class BulkService {

    static final int MAX_TICKETS = 1000;
    /** Above this many tickets, « ne pas envoyer de notifications » is the default. */
    static final int QUIET_ABOVE = 20;

    private static final Logger log = LoggerFactory.getLogger(BulkService.class);

    private final BulkJobRepository jobs;
    private final TicketService tickets;
    private final WatcherService watchers;
    private final PermissionService permissions;
    /** Jobs run on virtual threads; one job handles its tickets sequentially. */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    BulkService(BulkJobRepository jobs, TicketService tickets, WatcherService watchers, PermissionService permissions,
                PlatformTransactionManager txManager, JdbcClient jdbc, JsonMapper json) {
        this.jobs = jobs;
        this.tickets = tickets;
        this.watchers = watchers;
        this.permissions = permissions;
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Request(BulkJob.Operation operation, List<String> keys, Map<String, Object> params, Boolean sendNotifications) {}

    /** Records the job and starts it after commit; the caller gets the id right away. */
    public BulkJob start(AuthUser me, Request req) {
        if (req.operation() == null) throw Errors.badRequest("BULK_OPERATION", "Opération manquante");
        List<String> keys = req.keys() == null ? List.of() : req.keys().stream().distinct().toList();
        if (keys.isEmpty()) throw Errors.badRequest("BULK_EMPTY", "Aucun ticket sélectionné");
        if (keys.size() > MAX_TICKETS) throw Errors.badRequest("BULK_TOO_MANY", "Au plus " + MAX_TICKETS + " tickets à la fois");
        Map<String, Object> params = req.params() == null ? Map.of() : req.params();
        boolean notify = req.sendNotifications() != null ? req.sendNotifications() : keys.size() <= QUIET_ABOVE;
        BulkJob job = tx.execute(s -> jobs.save(new BulkJob(me.id(), req.operation(), params, keys.size())));
        Runnable run = () -> run(job.getId(), me, req.operation(), keys, params, notify);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(run);
                }
            });
        } else {
            executor.execute(run);
        }
        return job;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public BulkJob get(AuthUser me, UUID id) {
        BulkJob job = jobs.findById(id).orElseThrow(() -> Errors.notFound("Traitement introuvable"));
        if (!job.getUserId().equals(me.id())) throw Errors.notFound("Traitement introuvable");
        return job;
    }

    private void run(UUID jobId, AuthUser me, BulkJob.Operation op, List<String> keys, Map<String, Object> params, boolean notify) {
        List<BulkJob.Failure> failures = new ArrayList<>();
        int done = 0;
        for (String key : keys) {
            try {
                NotificationService.runMuted(!notify, () -> tx.executeWithoutResult(s -> apply(me, op, key, params)));
            } catch (RuntimeException e) {
                failures.add(new BulkJob.Failure(key, reason(e)));
            }
            done++;
            jdbc.sql("update bulk_jobs set done = ?, failed = ?, report = ?::jsonb where id = ?")
                    .params(done, failures.size(), json.writeValueAsString(failures), jobId).update();
        }
        jdbc.sql("update bulk_jobs set status = 'DONE', finished_at = now() where id = ?").param(jobId).update();
        log.info("Bulk {} {}: {} tickets, {} failed", op, jobId, keys.size(), failures.size());
    }

    private static String reason(RuntimeException e) {
        if (e instanceof ErrorResponseException r && r.getBody().getDetail() != null) return r.getBody().getDetail();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void apply(AuthUser me, BulkJob.Operation op, String key, Map<String, Object> p) {
        Ticket t = tickets.get(me, key);
        permissions.check(me, t.getSpace(), Perm.BULK_CHANGE);
        switch (op) {
            case EDIT -> tickets.update(me, key, editBody(t, p));
            case TRANSITION -> {
                String name = String.valueOf(p.get("statusName"));
                Status s = t.getSpace().workflowFor(t.getType()).getStatuses().stream()
                        .filter(x -> x.getName().equalsIgnoreCase(name)).findFirst()
                        .orElseThrow(() -> Errors.badRequest("INVALID_STATUS", "Statut « " + name + " » absent du workflow"));
                UUID resolution = p.get("resolutionId") == null ? null : UUID.fromString(p.get("resolutionId").toString());
                tickets.transition(me, key, s.getId(), resolution, null, (String) p.get("comment"));
            }
            case MOVE -> tickets.move(me, key, String.valueOf(p.get("spaceKey")),
                    p.get("typeId") == null ? null : UUID.fromString(p.get("typeId").toString()));
            case DELETE -> tickets.delete(me, key, Boolean.TRUE.equals(p.get("withSubtasks")));
            case WATCH -> watchers.watch(t.getId(), me.id());
            case UNWATCH -> watchers.unwatch(t.getId(), me.id());
        }
    }

    /** Components and versions: {@code {mode: ADD|REMOVE|REPLACE, values: [ids]}} like labels. */
    private void setOp(ObjectNode body, Map<String, Object> p, String field, List<String> currentIds) {
        if (!(p.get(field) instanceof Map<?, ?> op)) return;
        List<String> values = op.get("values") instanceof List<?> v ? v.stream().map(String::valueOf).toList() : List.of();
        Set<String> current = new LinkedHashSet<>(currentIds);
        switch (String.valueOf(op.get("mode"))) {
            case "ADD" -> current.addAll(values);
            case "REMOVE" -> values.forEach(current::remove);
            case "REPLACE" -> { current.clear(); current.addAll(values); }
            default -> throw Errors.badRequest("BULK_LABELS", "Mode attendu : ADD, REMOVE ou REPLACE");
        }
        body.set(field, json.valueToTree(current));
    }

    /**
     * EDIT params: {@code assigneeId}, {@code priorityId}, {@code parent} set as is; {@code labels: {mode, values}} adds,
     * removes or replaces labels.
     */
    private ObjectNode editBody(Ticket t, Map<String, Object> p) {
        ObjectNode body = json.createObjectNode();
        for (String f : List.of("assigneeId", "priorityId", "parent")) {
            if (p.containsKey(f)) body.set(f, json.valueToTree(p.get(f)));
        }
        if (p.get("labels") instanceof Map<?, ?> l) {
            List<String> values = l.get("values") instanceof List<?> v ? v.stream().map(String::valueOf).toList() : List.of();
            Set<String> current = new LinkedHashSet<>(t.getLabels().stream().map(Label::getName).toList());
            switch (String.valueOf(l.get("mode"))) {
                case "ADD" -> current.addAll(values);
                case "REMOVE" -> values.forEach(v -> current.removeIf(c -> c.equalsIgnoreCase(v)));
                case "REPLACE" -> { current.clear(); current.addAll(values); }
                default -> throw Errors.badRequest("BULK_LABELS", "Mode attendu : ADD, REMOVE ou REPLACE");
            }
            body.set("labels", json.valueToTree(current));
        }
        setOp(body, p, "componentIds", t.getComponents().stream().map(c -> c.getId().toString()).toList());
        setOp(body, p, "fixVersionIds", t.getFixVersions().stream().map(v -> v.getId().toString()).toList());
        if (body.isEmpty()) throw Errors.badRequest("BULK_NOTHING", "Aucune modification demandée");
        return body;
    }
}
