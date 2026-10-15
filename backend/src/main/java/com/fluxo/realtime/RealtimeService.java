package com.fluxo.realtime;

import com.fluxo.auth.AuthUser;
import com.fluxo.notification.TicketEvent;
import com.fluxo.space.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes "something changed" signals to browsers over Server-Sent Events, after commit (spec §8, V1 real time).
 * The payload only names the ticket; clients refetch through the normal, permission-checked API.
 */
@Service
public class RealtimeService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);
    private static final Duration SPACES_TTL = Duration.ofMinutes(1);

    /** Rank-only changes (board reorder) publish this; everything else goes through {@link TicketEvent}. */
    public record Changed(UUID spaceId, String spaceKey, String ticketKey, UUID actorId) {}

    public record Signal(String type, String spaceKey, String ticketKey, UUID actorId) {}

    private final class Client {
        final SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis()); // browser reconnects by itself
        final AuthUser user;
        volatile Set<UUID> spaces;
        volatile Instant spacesAt;

        Client(AuthUser user) {
            this.user = user;
            refresh();
        }

        void refresh() {
            spaces = permissions.visibleSpaces(user).keySet();
            spacesAt = Instant.now();
        }

        boolean sees(UUID spaceId) {
            // ponytail: visible spaces cached 1 min per connection, so a removed member may get signals (ids only) for up to a minute.
            if (Instant.now().isAfter(spacesAt.plus(SPACES_TTL))) refresh();
            return spaces.contains(spaceId);
        }
    }

    private final PermissionService permissions;
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();

    RealtimeService(PermissionService permissions) {
        this.permissions = permissions;
    }

    public SseEmitter subscribe(AuthUser user) {
        Client c = new Client(user);
        c.emitter.onCompletion(() -> clients.remove(c));
        c.emitter.onTimeout(() -> clients.remove(c));
        c.emitter.onError(e -> clients.remove(c));
        clients.add(c);
        send(c, SseEmitter.event().comment("connected"));
        return c.emitter;
    }

    @TransactionalEventListener
    void on(TicketEvent e) {
        var t = e.ticket();
        broadcast(t.getSpace().getId(), new Signal(e.type().name(), t.getSpace().getKey(), t.getKey(), e.actorId()));
    }

    @TransactionalEventListener
    void on(Changed e) {
        broadcast(e.spaceId(), new Signal("RANKED", e.spaceKey(), e.ticketKey(), e.actorId()));
    }

    private void broadcast(UUID spaceId, Signal s) {
        for (Client c : clients) {
            if (c.sees(spaceId)) send(c, SseEmitter.event().name("ticket").data(s));
        }
    }

    /** Keeps proxies from closing idle connections and detects dead clients. */
    @Scheduled(fixedRate = 25_000)
    void heartbeat() {
        for (Client c : clients) send(c, SseEmitter.event().comment("ping"));
    }

    int connections() {
        return clients.size();
    }

    private void send(Client c, SseEmitter.SseEventBuilder event) {
        try {
            c.emitter.send(event);
        } catch (IOException | IllegalStateException ex) {
            log.debug("SSE client gone: {}", ex.getMessage());
            clients.remove(c);
        }
    }
}
