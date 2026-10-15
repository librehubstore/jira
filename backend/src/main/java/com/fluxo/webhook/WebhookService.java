package com.fluxo.webhook;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.notification.TicketEvent;
import com.fluxo.space.SpaceRepository;
import com.fluxo.ticket.Ticket;
import com.fluxo.workflow.WorkflowWebhook;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Webhooks (spec §8): JSON payload signed with HMAC-SHA256 ({@code X-Fluxo-Signature: sha256=…}), sent after commit,
 * 3 attempts with backoff, every delivery logged for the admin.
 */
@Service
public class WebhookService {

    public static final List<String> EVENTS = List.of("ticket.created", "ticket.updated", "ticket.transitioned", "ticket.deleted",
            "comment.created", "comment.updated", "comment.deleted");
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Hook(UUID id, String url, String secret, List<String> events, String spaceKey, boolean active, Instant createdAt) {}

    public record Delivery(UUID id, String event, Integer statusCode, String error, int attempts, Long durationMs, Instant at) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SpaceRepository spaces;
    private final String appSecret;
    private final long[] backoff;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    WebhookService(JdbcClient jdbc, JsonMapper json, SpaceRepository spaces, @Value("${fluxo.secret}") String appSecret,
                   @Value("${fluxo.webhooks.backoff-ms:1000,5000}") long[] backoff) {
        this.jdbc = jdbc;
        this.json = json;
        this.spaces = spaces;
        this.appSecret = appSecret;
        this.backoff = backoff;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    // --- Administration (global admin) ---------------------------------------------

    public List<Hook> list(AuthUser me) {
        admin(me);
        return jdbc.sql("""
                select w.id, w.url, w.secret, w.events::text as events, s.key as space_key, w.active, w.created_at
                from webhooks w left join spaces s on s.id = w.space_id order by w.created_at""")
                .query((rs, i) -> new Hook(rs.getObject("id", UUID.class), rs.getString("url"), rs.getString("secret"),
                        List.of(json.readValue(rs.getString("events"), String[].class)), rs.getString("space_key"), rs.getBoolean("active"),
                        rs.getTimestamp("created_at").toInstant())).list();
    }

    public Hook create(AuthUser me, String url, List<String> events, String spaceKey) {
        admin(me);
        checkUrl(url);
        List<String> ev = events == null ? List.of() : events;
        if (!EVENTS.containsAll(ev)) throw Errors.badRequest("INVALID_EVENT", "Événements possibles : " + EVENTS);
        UUID spaceId = spaceKey == null || spaceKey.isBlank() ? null
                : spaces.findByKey(spaceKey).orElseThrow(() -> Errors.badRequest("UNKNOWN_SPACE", "Espace inconnu : " + spaceKey)).getId();
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String secret = HexFormat.of().formatHex(raw);
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into webhooks (id, url, secret, events, space_id) values (?, ?, ?, ?::jsonb, ?)")
                .params(id, url.strip(), secret, json.writeValueAsString(ev), spaceId).update();
        return list(me).stream().filter(h -> h.id().equals(id)).findFirst().orElseThrow();
    }

    public void setActive(AuthUser me, UUID id, boolean active) {
        admin(me);
        if (jdbc.sql("update webhooks set active = ? where id = ?").params(active, id).update() == 0) throw Errors.notFound("Webhook introuvable");
    }

    public void delete(AuthUser me, UUID id) {
        admin(me);
        jdbc.sql("delete from webhooks where id = ?").param(id).update();
    }

    public List<Delivery> deliveries(AuthUser me, UUID id) {
        admin(me);
        return jdbc.sql("select id, event, status_code, error, attempts, duration_ms, created_at from webhook_deliveries where webhook_id = ? order by created_at desc limit 50")
                .param(id).query((rs, i) -> new Delivery(rs.getObject("id", UUID.class), rs.getString("event"), (Integer) rs.getObject("status_code"),
                        rs.getString("error"), rs.getInt("attempts"), (Long) rs.getObject("duration_ms"), rs.getTimestamp("created_at").toInstant())).list();
    }

    // ponytail: admin-only URLs, so no SSRF filtering of private addresses; add one if non-admins ever configure hooks.
    private static void checkUrl(String url) {
        if (url == null || !url.strip().matches("https?://\\S+") || url.length() > 2000) throw Errors.badRequest("INVALID_URL", "URL http(s) attendue");
    }

    private static void admin(AuthUser me) {
        if (!me.admin()) throw Errors.forbidden("Réservé à l'administrateur");
    }

    // --- Sending -------------------------------------------------------------------

    static String event(TicketEvent.Type type) {
        return switch (type) {
            case CREATED -> "ticket.created";
            case UPDATED, ASSIGNED, MOVED -> "ticket.updated";
            case TRANSITIONED, RESOLVED, REOPENED -> "ticket.transitioned";
            case DELETED -> "ticket.deleted";
            case COMMENTED -> "comment.created";
            case COMMENT_EDITED -> "comment.updated";
            case COMMENT_DELETED -> "comment.deleted";
            default -> null;
        };
    }

    /** The payload is built inside the transaction (lazy fields readable), the requests go after commit. */
    @EventListener
    void on(TicketEvent e) {
        String event = event(e.type());
        if (event == null) return;
        Ticket t = e.ticket();
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("key", t.getKey());
        ticket.put("summary", t.getSummary());
        ticket.put("space", t.getSpace().getKey());
        ticket.put("status", t.getStatus().getName());
        ticket.put("assignee", t.getAssignee() == null ? null : t.getAssignee().getUsername());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("at", Instant.now().toString());
        payload.put("actorId", e.actorId());
        payload.put("ticket", ticket);
        payload.put("changes", e.changes());
        if (e.excerpt() != null) payload.put("comment", e.excerpt()); // restricted comments carry no text
        byte[] body = json.writeValueAsBytes(payload);
        UUID spaceId = t.getSpace().getId();
        List<Hook> hooks = jdbc.sql("""
                        select id, url, secret, events::text as events from webhooks
                        where active and (space_id is null or space_id = ?)""").param(spaceId)
                .query((rs, i) -> new Hook(rs.getObject("id", UUID.class), rs.getString("url"), rs.getString("secret"),
                        List.of(json.readValue(rs.getString("events"), String[].class)), null, true, null)).list();
        List<Hook> targets = hooks.stream().filter(h -> h.events().isEmpty() || h.events().contains(event)).toList();
        if (targets.isEmpty()) return;
        Runnable send = () -> targets.forEach(h -> executor.execute(() -> deliver(h.id(), h.url(), h.secret(), event, body)));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    /** « Envoyer un webhook » transition action: signed with the application secret. */
    @TransactionalEventListener
    void on(WorkflowWebhook w) {
        byte[] body = json.writeValueAsBytes(Map.of("event", "workflow.transition", "transition", w.transition(),
                "ticket", w.ticketKey(), "actorId", w.actorId(), "at", Instant.now().toString()));
        executor.execute(() -> deliver(null, w.url(), appSecret, "workflow.transition", body));
    }

    void deliver(UUID hookId, String url, String secret, String event, byte[] body) {
        String signature = "sha256=" + hmac(secret, body);
        Integer status = null;
        String error = null;
        int attempt = 0;
        long start = System.nanoTime();
        while (attempt < 3) {
            attempt++;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").header("User-Agent", "Fluxo-Webhook")
                        .header("X-Fluxo-Event", event).header("X-Fluxo-Signature", signature)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                error = status / 100 == 2 ? null : "HTTP " + status;
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                status = null;
                error = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
            }
            if (error == null) break;
            if (attempt < 3) {
                try {
                    Thread.sleep(backoff[Math.min(attempt - 1, backoff.length - 1)]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (hookId != null) {
            jdbc.sql("insert into webhook_deliveries (id, webhook_id, event, status_code, error, attempts, duration_ms) values (?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID(), hookId, event, status, error == null ? null : error.substring(0, Math.min(error.length(), 500)), attempt, ms).update();
        }
        if (error != null) log.warn("Webhook {} to {} failed after {} attempts: {}", event, url, attempt, error);
    }

    static String hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
