package com.fluxo.collab;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.notification.TicketEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.ticket.HistoryEntry;
import com.fluxo.ticket.HistoryRepository;
import com.fluxo.ticket.Ticket;
import com.fluxo.ticket.TicketService;
import com.fluxo.user.User;
import com.fluxo.user.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Comments, @mentions and reactions (spec §4.4). */
@Service
@Transactional
public class CommentService {

    static final int MAX_BODY = 100_000;
    private static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([a-zA-Z0-9._-]{2,64})");
    private static final Set<String> EMOJIS = Set.of("👍", "👎", "❤️", "🎉", "😄", "😕", "👀", "🚀");

    private final CommentRepository comments;
    private final TicketService tickets;
    private final PermissionService permissions;
    private final WatcherService watchers;
    private final UserRepository users;
    private final HistoryRepository history;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    CommentService(CommentRepository comments, TicketService tickets, PermissionService permissions,
                   WatcherService watchers, UserRepository users, HistoryRepository history, JdbcClient jdbc, Clock clock,
                   ApplicationEventPublisher events) {
        this.comments = comments;
        this.tickets = tickets;
        this.permissions = permissions;
        this.watchers = watchers;
        this.users = users;
        this.history = history;
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
    }

    public record Added(Comment comment, List<User> mentioned) {}

    public Added add(AuthUser me, String ticketKey, String body) {
        return add(me, ticketKey, body, null);
    }

    /**
     * {@code visibility}: null = the space default, "" = everyone, else a role id the author holds (spec §4.4).
     * A restricted comment only notifies people who can read it, and without its text.
     */
    public Added add(AuthUser me, String ticketKey, String body, String visibility) {
        Ticket t = tickets.get(me, ticketKey);
        permissions.check(me, t.getSpace(), Perm.COMMENT_ADD);
        Comment c = new Comment(t, users.getReferenceById(me.id()), body(body));
        c.setVisibilityRoleId(role(me, t, visibility == null ? (String) t.getSpace().getSettings().get("commentVisibilityRoleId") : visibility));
        comments.save(c);
        watchers.autoWatch(t.getId(), me.id());
        List<User> mentioned = mentions(t, c.getBody(), me).stream().filter(u -> canSee(AuthUser.of(u), c)).toList();
        mentioned.forEach(u -> watchers.watch(t.getId(), u.getId()));
        events.publishEvent(new TicketEvent(TicketEvent.Type.COMMENTED, t, me.id(), List.of(),
                c.getVisibilityRoleId() == null ? excerpt(c.getBody()) : null, Set.copyOf(mentioned), null));
        return new Added(c, mentioned);
    }

    private UUID role(AuthUser me, Ticket t, String visibility) {
        if (visibility == null || visibility.isBlank()) return null;
        UUID role;
        try {
            role = UUID.fromString(visibility);
        } catch (IllegalArgumentException e) {
            throw Errors.badRequest("INVALID_ROLE", "Rôle inconnu");
        }
        long inSpace = jdbc.sql("select count(*) from space_roles where id = ? and space_id = ?").params(role, t.getSpace().getId()).query(Long.class).optional().orElse(0L);
        if (inSpace == 0) throw Errors.badRequest("INVALID_ROLE", "Rôle inconnu dans cet espace");
        if (!permissions.roleIds(me, t.getSpace()).contains(role) && !permissions.has(me, t.getSpace(), Perm.ADMINISTER_SPACE)) {
            throw Errors.forbidden("Vous ne pouvez restreindre qu'à un rôle que vous avez");
        }
        return role;
    }

    /** Author, members of the role and space admins read a restricted comment. */
    public boolean canSee(AuthUser user, Comment c) {
        if (c.getVisibilityRoleId() == null || c.getAuthor().getId().equals(user.id())) return true;
        var space = c.getTicket().getSpace();
        return permissions.roleIds(user, space).contains(c.getVisibilityRoleId()) || permissions.has(user, space, Perm.ADMINISTER_SPACE);
    }

    public Added edit(AuthUser me, UUID commentId, String body) {
        Comment c = comment(me, commentId);
        boolean own = c.getAuthor().getId().equals(me.id());
        permissions.check(me, c.getTicket().getSpace(), own ? Perm.COMMENT_EDIT_OWN : Perm.COMMENT_EDIT_ALL);
        Set<String> before = mentionNames(c.getBody());
        c.setBody(body(body));
        c.setEditedAt(clock.instant());
        // Only newly added mentions are notified.
        List<User> mentioned = mentions(c.getTicket(), c.getBody(), me).stream()
                .filter(u -> !before.contains(u.getUsername().toLowerCase())).toList();
        mentioned.forEach(u -> watchers.watch(c.getTicket().getId(), u.getId()));
        events.publishEvent(new TicketEvent(TicketEvent.Type.COMMENT_EDITED, c.getTicket(), me.id(), List.of(),
                c.getVisibilityRoleId() == null ? excerpt(c.getBody()) : null,
                Set.copyOf(mentioned), null));
        return new Added(c, mentioned);
    }

    public void delete(AuthUser me, UUID commentId) {
        Comment c = comment(me, commentId);
        boolean own = c.getAuthor().getId().equals(me.id());
        permissions.check(me, c.getTicket().getSpace(), own ? Perm.COMMENT_DELETE_OWN : Perm.COMMENT_DELETE_ALL);
        history.save(new HistoryEntry(c.getTicket(), users.getReferenceById(me.id()), "comment", c.getId().toString(), null,
                "commentaire de " + c.getAuthor().getDisplayName(), null));
        comments.delete(c);
        events.publishEvent(TicketEvent.of(TicketEvent.Type.COMMENT_DELETED, c.getTicket(), me.id()));
    }

    /** Toggles the user's reaction; any space member who can browse may react. */
    public void toggleReaction(AuthUser me, UUID commentId, String emoji) {
        Comment c = comment(me, commentId);
        if (!EMOJIS.contains(emoji)) throw Errors.badRequest("INVALID_EMOJI", "Réaction non prise en charge");
        int removed = jdbc.sql("delete from reactions where comment_id = ? and user_id = ? and emoji = ?")
                .params(c.getId(), me.id(), emoji).update();
        if (removed == 0) {
            jdbc.sql("insert into reactions (comment_id, user_id, emoji) values (?, ?, ?)").params(c.getId(), me.id(), emoji).update();
        }
    }

    public record ReactionCount(String emoji, int count, boolean mine) {}

    @Transactional(readOnly = true)
    public Map<UUID, List<ReactionCount>> reactions(Collection<UUID> commentIds, UUID me) {
        Map<UUID, List<ReactionCount>> result = new HashMap<>();
        if (commentIds.isEmpty()) return result;
        jdbc.sql("""
                        select comment_id, emoji, count(*) as n, bool_or(user_id = :me) as mine
                        from reactions where comment_id in (:ids) group by comment_id, emoji order by min(emoji)
                        """)
                .param("me", me).param("ids", commentIds)
                .query((rs, i) -> Map.entry(rs.getObject("comment_id", UUID.class),
                        new ReactionCount(rs.getString("emoji"), rs.getInt("n"), rs.getBoolean("mine"))))
                .list()
                .forEach(e -> result.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Comment> list(AuthUser me, String ticketKey) {
        return comments.findByTicket(tickets.get(me, ticketKey).getId()).stream().filter(c -> canSee(me, c)).toList();
    }

    private Comment comment(AuthUser me, UUID id) {
        Comment c = comments.findById(id).orElseThrow(() -> Errors.notFound("Commentaire introuvable"));
        if (!permissions.has(me, c.getTicket().getSpace(), Perm.BROWSE) || !canSee(me, c)) throw Errors.notFound("Commentaire introuvable");
        return c;
    }

    /** Mentioned users who can see the ticket (spec §4.4: only space members), excluding the author. */
    private List<User> mentions(Ticket t, String body, AuthUser me) {
        List<User> result = new ArrayList<>();
        for (String name : mentionNames(body)) {
            users.findByUsernameIgnoreCase(name)
                    .filter(User::isActive)
                    .filter(u -> !u.getId().equals(me.id()))
                    .filter(u -> permissions.has(AuthUser.of(u), t.getSpace(), Perm.BROWSE))
                    .ifPresent(result::add);
        }
        return result;
    }

    static Set<String> mentionNames(String body) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(body);
        while (m.find()) names.add(m.group(1).toLowerCase());
        return names;
    }

    private static String excerpt(String body) {
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    private static String body(String body) {
        String b = body == null ? "" : body.strip();
        if (b.isEmpty()) throw Errors.badRequest("EMPTY_COMMENT", "Le commentaire est vide");
        if (b.length() > MAX_BODY) throw Errors.badRequest("COMMENT_TOO_LONG", "Commentaire limité à " + MAX_BODY + " caractères");
        return b;
    }
}
