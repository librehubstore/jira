package com.fluxo.notification;

import com.fluxo.auth.AuthUser;
import com.fluxo.collab.WatcherService;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.ticket.Ticket;
import com.fluxo.user.User;
import com.fluxo.user.UserRepository;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.fluxo.notification.TicketEvent.Type.*;

/** Turns ticket events into in-app notifications (spec §9). */
@Service
@Transactional
public class NotificationService {

    static final Duration GROUPING_WINDOW = Duration.ofMinutes(2);

    private final NotificationRepository notifications;
    private final WatcherService watchers;
    private final PermissionService permissions;
    private final UserRepository users;
    private final Clock clock;

    private final EmailService email;
    private final com.fluxo.ticket.TicketRepository tickets;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    NotificationService(NotificationRepository notifications, WatcherService watchers, PermissionService permissions,
                        UserRepository users, Clock clock, EmailService email, com.fluxo.ticket.TicketRepository tickets,
                        org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.email = email;
        this.tickets = tickets;
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.watchers = watchers;
        this.permissions = permissions;
        this.users = users;
        this.clock = clock;
    }

    /** Set by bulk changes run with « ne pas envoyer de notifications » (spec §4.12). */
    private static final ThreadLocal<Boolean> MUTED = ThreadLocal.withInitial(() -> false);

    public static void runMuted(boolean muted, Runnable work) {
        MUTED.set(muted);
        try {
            work.run();
        } finally {
            MUTED.remove();
        }
    }

    @EventListener
    public void on(TicketEvent e) {
        if (MUTED.get()) return;
        Ticket t = e.ticket();
        Map<UUID, TicketEvent.Type> recipients = new LinkedHashMap<>();
        Set<UUID> watcherIds = new LinkedHashSet<>(watchers.watcherIds(t.getId()));
        UUID assignee = t.getAssignee() == null ? null : t.getAssignee().getId();
        UUID reporter = t.getReporter().getId();
        switch (e.type()) {
            case CREATED -> add(recipients, e.type(), watcherIds, assignee);
            case UPDATED, DELETED, MOVED -> add(recipients, e.type(), watcherIds);
            case ASSIGNED -> {
                add(recipients, UPDATED, watcherIds);
                add(recipients, ASSIGNED, assignee, e.previousAssignee() == null ? null : e.previousAssignee().getId());
            }
            case TRANSITIONED, RESOLVED, REOPENED, COMMENTED, COMMENT_EDITED -> add(recipients, e.type(), watcherIds, assignee, reporter);
            case MENTIONED, BLOCKER_RESOLVED, COMMENT_DELETED -> { }
        }
        e.mentioned().forEach(u -> recipients.put(u.getId(), MENTIONED)); // a mention always wins
        recipients.remove(e.actorId()); // never notify the author of the action

        User actor = users.getReferenceById(e.actorId());
        for (var entry : recipients.entrySet()) {
            User u = users.findById(entry.getKey()).orElse(null);
            if (u == null || !u.isActive() || !permissions.has(AuthUser.of(u), t.getSpace(), Perm.BROWSE)) continue;
            deliver(u, actor, t, entry.getValue(), e);
        }
        if (e.type() == RESOLVED) blockerResolved(t, e);
    }

    /** In-app and email follow separate preferences (spec §9). */
    private void deliver(User u, User actor, Ticket t, TicketEvent.Type type, TicketEvent e) {
        if (wants(u, type, t, "notifications")) notify(u, actor, t, type, e);
        if (wants(u, type, t, "emailNotifications")) email.enqueue(u, t, line(actor, type, e));
    }

    /** « Bloquant résolu » (V1): watchers of the tickets this one was blocking are told they may go on. */
    private void blockerResolved(Ticket blocker, TicketEvent e) {
        List<UUID> blocked = jdbc.sql("""
                select l.target_id from ticket_links l join link_types lt on lt.id = l.link_type_id
                where lt.name = 'Blocks' and l.source_id = ?""").param(blocker.getId()).query(UUID.class).list();
        User actor = users.getReferenceById(e.actorId());
        TicketEvent signal = new TicketEvent(BLOCKER_RESOLVED, blocker, e.actorId(), List.of(), blocker.getKey() + " " + blocker.getSummary(), Set.of(), null);
        for (Ticket t : tickets.findAllById(blocked)) {
            for (UUID id : watchers.watcherIds(t.getId())) {
                if (id.equals(e.actorId())) continue;
                User u = users.findById(id).orElse(null);
                if (u == null || !u.isActive() || !permissions.has(AuthUser.of(u), t.getSpace(), Perm.BROWSE)) continue;
                deliver(u, actor, t, BLOCKER_RESOLVED, signal);
            }
        }
    }

    private static final Map<TicketEvent.Type, String> LABELS = Map.ofEntries(
            Map.entry(CREATED, "a créé le ticket"), Map.entry(UPDATED, "a modifié le ticket"), Map.entry(ASSIGNED, "a changé l'assigné"),
            Map.entry(TRANSITIONED, "a changé le statut"), Map.entry(RESOLVED, "a résolu le ticket"), Map.entry(REOPENED, "a rouvert le ticket"),
            Map.entry(COMMENTED, "a commenté"), Map.entry(COMMENT_EDITED, "a modifié un commentaire"), Map.entry(MENTIONED, "vous a mentionné"),
            Map.entry(DELETED, "a supprimé le ticket"), Map.entry(MOVED, "a déplacé le ticket"), Map.entry(BLOCKER_RESOLVED, "a résolu un ticket bloquant :"), Map.entry(COMMENT_DELETED, "a supprimé un commentaire"));

    /** One email line: who did what, the changed fields and the comment excerpt. */
    private static String line(User actor, TicketEvent.Type type, TicketEvent e) {
        StringBuilder s = new StringBuilder(actor.getDisplayName()).append(' ').append(LABELS.getOrDefault(type, type.name()));
        for (TicketEvent.Change c : e.changes()) {
            s.append("\n  • ").append(c.field()).append(" : ").append(c.from() == null ? "—" : c.from()).append(" → ").append(c.to() == null ? "—" : c.to());
        }
        if (e.excerpt() != null) s.append("\n  « ").append(e.excerpt()).append(" »");
        return s.toString();
    }

    private void notify(User user, User actor, Ticket t, TicketEvent.Type type, TicketEvent e) {
        Instant now = clock.instant();
        List<Map<String, Object>> changes = e.changes().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", c.field());
            m.put("from", c.from());
            m.put("to", c.to());
            return m;
        }).toList();
        if (type != DELETED) {
            Optional<Notification> group = notifications.findGroupable(user.getId(), t.getId(), actor.getId(), type.name(), now.minus(GROUPING_WINDOW));
            if (group.isPresent()) {
                Notification n = group.get();
                Map<String, Object> payload = new LinkedHashMap<>(n.getPayload());
                List<Object> merged = new ArrayList<>((List<?>) payload.getOrDefault("changes", List.of()));
                merged.addAll(changes);
                payload.put("changes", merged);
                payload.put("count", ((Number) payload.getOrDefault("count", 1)).intValue() + 1);
                payload.put("summary", t.getSummary());
                if (e.excerpt() != null) payload.put("excerpt", e.excerpt());
                n.setPayload(payload);
                n.setUpdatedAt(now);
                return;
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketKey", t.getKey());
        payload.put("summary", t.getSummary());
        payload.put("changes", changes);
        payload.put("count", 1);
        if (e.excerpt() != null) payload.put("excerpt", e.excerpt());
        notifications.save(new Notification(user, actor, type == DELETED ? null : t, type.name(), payload));
    }

    /**
     * Per-user preferences (spec §9): {@code notifications: {EVENT: false}} mutes an event in-app,
     * {@code emailNotifications: {EVENT: false}} by email;
     * {@code notifyOnlyDirect: true} keeps mentions, assignments and tickets assigned to the user.
     */
    private static boolean wants(User u, TicketEvent.Type type, Ticket t, String channel) {
        Map<String, Object> prefs = u.getPreferences();
        if (prefs.get(channel) instanceof Map<?, ?> perEvent && Boolean.FALSE.equals(perEvent.get(type.name()))) return false;
        if (Boolean.TRUE.equals(prefs.get("notifyOnlyDirect"))) {
            boolean assignedToMe = t.getAssignee() != null && t.getAssignee().getId().equals(u.getId());
            return type == MENTIONED || type == ASSIGNED || assignedToMe;
        }
        return true;
    }

    private static void add(Map<UUID, TicketEvent.Type> recipients, TicketEvent.Type type, Collection<UUID> ids) {
        ids.forEach(id -> recipients.putIfAbsent(id, type));
    }

    private static void add(Map<UUID, TicketEvent.Type> recipients, TicketEvent.Type type, Collection<UUID> ids, UUID... more) {
        add(recipients, type, ids);
        add(recipients, type, more);
    }

    private static void add(Map<UUID, TicketEvent.Type> recipients, TicketEvent.Type type, UUID... ids) {
        for (UUID id : ids) if (id != null) recipients.put(id, type);
    }

    // --- Reading ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Notification> list(AuthUser me, boolean unreadOnly, int limit) {
        return notifications.findForUser(me.id(), unreadOnly, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)));
    }

    @Transactional(readOnly = true)
    public long unreadCount(AuthUser me) {
        return notifications.countByUserIdAndReadAtIsNull(me.id());
    }

    public void markRead(AuthUser me, UUID id, boolean read) {
        Notification n = notifications.findById(id).filter(x -> x.getUser().getId().equals(me.id()))
                .orElseThrow(() -> Errors.notFound("Notification introuvable"));
        n.setReadAt(read ? clock.instant() : null);
    }

    public int markAllRead(AuthUser me) {
        return notifications.markAllRead(me.id(), clock.instant());
    }
}
