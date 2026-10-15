package com.fluxo.search;

import com.fluxo.field.FieldService;
import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.ticket.Ticket;
import com.fluxo.ticket.TicketRepository;
import com.fluxo.search.fql.Fql;
import com.fluxo.search.fql.FqlCompiler;
import com.fluxo.user.User;
import com.fluxo.user.UserRepository;
import com.fluxo.workflow.StatusCategory;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Structured search (spec §4.11, MVP): filters → JPA Specification, text → PostgreSQL full-text on
 * summary, description and comments. Always restricted to spaces the user can browse. An FQL query
 * (V1) is compiled to a Specification and combined (AND) with the structured filters.
 */
@Service
@Transactional(readOnly = true)
public class SearchService {

    public static final int MAX_EXPORT = 10_000;
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9]{1,9}-\\d+");
    private static final Map<String, String> SORTABLE = Map.ofEntries(
            Map.entry("key", "number"), Map.entry("summary", "summary"), Map.entry("created", "createdAt"),
            Map.entry("updated", "updatedAt"), Map.entry("due", "dueDate"), Map.entry("resolved", "resolvedAt"),
            Map.entry("priority", "priority.rank"), Map.entry("status", "status.name"), Map.entry("type", "type.name"),
            Map.entry("assignee", "assignee.displayName"), Map.entry("reporter", "reporter.displayName"),
            Map.entry("space", "space.key"), Map.entry("rank", "rank"));

    private final TicketRepository tickets;
    private final PermissionService permissions;
    private final JdbcClient jdbc;
    private final FieldService fields;
    private final UserRepository users;

    SearchService(TicketRepository tickets, PermissionService permissions, JdbcClient jdbc, UserRepository users, FieldService fields) {
        this.fields = fields;
        this.tickets = tickets;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.users = users;
    }

    /** Filter values; null or empty = no constraint. Within a field: OR, across fields: AND. */
    public record Criteria(String q, List<String> spaces, List<UUID> types, List<UUID> statuses,
                           List<StatusCategory> statusCategories, List<String> assignees, List<UUID> reporters,
                           List<UUID> priorities, List<String> labels, String parent,
                           LocalDate createdFrom, LocalDate createdTo, LocalDate updatedFrom, LocalDate updatedTo,
                           LocalDate dueFrom, LocalDate dueTo, Boolean resolved, String fql) {}

    public Page<Ticket> search(AuthUser me, Criteria c, String sort, int page, int size) {
        Fql.Query fql = parse(c.fql());
        return tickets.findAll(spec(me, c).and(fql(me, fql)),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 1000), sort(fql, sort)));
    }

    public List<Ticket> export(AuthUser me, Criteria c, String sort) {
        Fql.Query fql = parse(c.fql());
        return tickets.findAll(spec(me, c).and(fql(me, fql)), PageRequest.of(0, MAX_EXPORT, sort(fql, sort))).getContent();
    }

    /** Ids of the given candidates that match an FQL query (board filters and swimlanes). */
    public Set<UUID> matching(AuthUser me, String fql, Collection<UUID> candidates) {
        if (candidates.isEmpty()) return Set.of();
        Specification<Ticket> spec = fql(me, parse(fql)).and((root, q, cb) -> root.get("id").in(candidates));
        return new HashSet<>(tickets.findAll(spec).stream().map(Ticket::getId).toList());
    }

    /** Validates an FQL query; throws a 400 FQL_ERROR with the position of the problem. */
    public Fql.Query parse(String fql) {
        if (fql == null || fql.isBlank()) return null;
        try {
            return Fql.parse(fql);
        } catch (Fql.ParseException e) {
            throw fqlError(e);
        }
    }

    private Specification<Ticket> fql(AuthUser me, Fql.Query q) {
        if (q == null || q.where() == null) return (root, query, cb) -> cb.conjunction();
        Specification<Ticket> spec = new FqlCompiler(context(me)).where(q.where());
        return (root, query, cb) -> {
            try {
                return spec.toPredicate(root, query, cb);
            } catch (Fql.ParseException e) {
                throw fqlError(e);
            }
        };
    }

    private Sort sort(Fql.Query q, String sort) {
        if (q == null || q.orderBy().isEmpty()) return sort(sort);
        try {
            return FqlCompiler.sort(q.orderBy()).and(Sort.by(Sort.Order.asc("space.key"), Sort.Order.asc("number")));
        } catch (Fql.ParseException e) {
            throw fqlError(e);
        }
    }

    private static RuntimeException fqlError(Fql.ParseException e) {
        var err = Errors.badRequest("FQL_ERROR", e.getMessage());
        err.getBody().setProperty("position", e.position());
        return err;
    }

    private FqlCompiler.Context context(AuthUser me) {
        Set<UUID> visible = permissions.visibleSpaces(me).keySet();
        return new FqlCompiler.Context() {
            public AuthUser me() { return me; }

            public ZoneId zone() { return ZoneId.systemDefault(); }

            public List<UUID> users(String name) {
                return users.findAll().stream()
                        .filter(u -> u.getUsername().equalsIgnoreCase(name) || u.getDisplayName().equalsIgnoreCase(name))
                        .map(User::getId).toList();
            }

            public List<UUID> groupMembers(String group) {
                return jdbc.sql("select gm.user_id from group_members gm join groups g on g.id = gm.group_id where lower(g.name) = lower(?)")
                        .param(group).query(UUID.class).list();
            }

            public Optional<FieldService.CustomField> customField(String name) {
                return fields.byName(name);
            }

            public Set<UUID> customMatches(FieldService.CustomField f, String op, List<String> values) {
                return fields.matches(f, op, values);
            }

            public Set<UUID> textMatches(String field, String query) {
                return SearchService.this.textMatches(query, visible, field);
            }

            public Set<UUID> watchedBy(UUID userId) {
                return new HashSet<>(jdbc.sql("select ticket_id from watchers where user_id = ?").param(userId).query(UUID.class).list());
            }

            public Integer priorityRank(String name) {
                return jdbc.sql("select rank from priorities where lower(name) = lower(?)").param(name).query(Integer.class).optional().orElse(null);
            }

            public UUID ticketId(String key) {
                int dash = key.lastIndexOf('-');
                if (dash < 1 || !key.substring(dash + 1).matches("\\d+")) return null;
                return tickets.findByKey(key.substring(0, dash), Integer.parseInt(key.substring(dash + 1))).map(Ticket::getId)
                        .or(() -> tickets.findIdByAlias(key)).orElse(null);
            }

            public Set<UUID> updatedBy(UUID userId, Instant from, Instant to) {
                return new HashSet<>(jdbc.sql("select distinct ticket_id from history_entries where actor_id = ? and at >= ? and at < ?")
                        .params(userId, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to)).query(UUID.class).list());
            }

            public Set<UUID> linkedTo(UUID ticketId, String label) {
                return new HashSet<>(jdbc.sql("""
                                select case when l.source_id = :t then l.target_id else l.source_id end
                                from ticket_links l join link_types lt on lt.id = l.link_type_id
                                where (l.source_id = :t or l.target_id = :t)
                                  and (cast(:label as text) is null
                                       or (l.source_id = :t and lower(lt.outward) = lower(:label))
                                       or (l.target_id = :t and lower(lt.inward) = lower(:label))
                                       or lower(lt.name) = lower(:label))
                                """).param("t", ticketId).param("label", label).query(UUID.class).list());
            }
        };
    }

    /** Exact key match first (opens the ticket directly), then text matches. */
    public record Quick(String exactKey, List<Ticket> results) {}

    public Quick quick(AuthUser me, String q) {
        String text = q == null ? "" : q.strip();
        String exact = KEY.matcher(text).matches() ? resolveKey(me, text) : null;
        List<Ticket> results = text.isEmpty() ? List.of() : search(me, new Criteria(text, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null), "updated,desc", 0, 10).getContent();
        return new Quick(exact, results);
    }

    private String resolveKey(AuthUser me, String key) {
        try {
            var t = tickets.findByKey(key.substring(0, key.lastIndexOf('-')).toUpperCase(), Integer.parseInt(key.substring(key.lastIndexOf('-') + 1)));
            return t.filter(x -> permissions.has(me, x.getSpace(), Perm.BROWSE)).map(Ticket::getKey).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Specification<Ticket> spec(AuthUser me, Criteria c) {
        Set<UUID> visible = permissions.visibleSpaces(me).keySet();
        Set<UUID> textIds = c.q() == null || c.q().isBlank() ? null : textMatches(c.q(), visible);
        return (root, query, cb) -> {
            if (query != null && Long.class != query.getResultType()) {
                root.fetch("status");
                root.fetch("type");
                root.fetch("priority");
                root.fetch("space");
                root.fetch("assignee", JoinType.LEFT);
                root.fetch("reporter");
            }
            List<Predicate> p = new ArrayList<>();
            p.add(visible.isEmpty() ? cb.disjunction() : root.get("space").get("id").in(visible));
            if (textIds != null) p.add(textIds.isEmpty() ? cb.disjunction() : root.get("id").in(textIds));
            // Archived tickets only when asked for (FQL « archived = true »).
            if (c.fql() == null || !c.fql().toLowerCase(Locale.ROOT).contains("archived")) p.add(cb.isFalse(root.get("archived")));
            if (notEmpty(c.spaces())) p.add(root.get("space").get("key").in(c.spaces()));
            if (notEmpty(c.types())) p.add(root.get("type").get("id").in(c.types()));
            if (notEmpty(c.statuses())) p.add(root.get("status").get("id").in(c.statuses()));
            if (notEmpty(c.statusCategories())) p.add(root.get("status").get("category").in(c.statusCategories()));
            if (notEmpty(c.priorities())) p.add(root.get("priority").get("id").in(c.priorities()));
            if (notEmpty(c.reporters())) p.add(root.get("reporter").get("id").in(c.reporters()));
            if (notEmpty(c.assignees())) {
                List<Predicate> or = new ArrayList<>();
                List<UUID> ids = new ArrayList<>();
                for (String a : c.assignees()) {
                    if (a.equals("none")) or.add(cb.isNull(root.get("assignee")));
                    else if (a.equals("me")) ids.add(me.id());
                    else ids.add(UUID.fromString(a));
                }
                if (!ids.isEmpty()) or.add(root.get("assignee").get("id").in(ids));
                p.add(cb.or(or.toArray(Predicate[]::new)));
            }
            if (notEmpty(c.labels())) {
                var sub = query.subquery(UUID.class);
                var t = sub.from(Ticket.class);
                var l = t.join("labels");
                sub.select(t.get("id")).where(cb.lower(l.get("name")).in(c.labels().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()));
                p.add(root.get("id").in(sub));
            }
            if (c.parent() != null && !c.parent().isBlank()) {
                var parent = root.join("parent", JoinType.INNER);
                String key = c.parent().toUpperCase(Locale.ROOT);
                int dash = key.lastIndexOf('-');
                if (dash < 1) throw Errors.badRequest("INVALID_PARENT", "Clé de parent invalide");
                p.add(cb.equal(parent.get("space").get("key"), key.substring(0, dash)));
                p.add(cb.equal(parent.get("number"), Integer.parseInt(key.substring(dash + 1))));
            }
            dateRange(p, cb, root.get("createdAt"), c.createdFrom(), c.createdTo());
            dateRange(p, cb, root.get("updatedAt"), c.updatedFrom(), c.updatedTo());
            if (c.dueFrom() != null) p.add(cb.greaterThanOrEqualTo(root.get("dueDate"), c.dueFrom()));
            if (c.dueTo() != null) p.add(cb.lessThanOrEqualTo(root.get("dueDate"), c.dueTo()));
            if (c.resolved() != null) p.add(c.resolved() ? cb.isNotNull(root.get("resolvedAt")) : cb.isNull(root.get("resolvedAt")));
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    /**
     * Ticket ids whose summary, description or comments match every word (prefix match).
     * ponytail: capped at 5 000 ids; past that, a join on the tsvector inside the main query is needed.
     */
    private Set<UUID> textMatches(String text, Set<UUID> visibleSpaces) {
        return textMatches(text, visibleSpaces, "text");
    }

    /** field: summary, description, comment, or text (summary + description + comments). */
    private Set<UUID> textMatches(String text, Set<UUID> visibleSpaces, String field) {
        List<String> words = Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")).filter(w -> !w.isBlank()).toList();
        if (words.isEmpty() || visibleSpaces.isEmpty()) return Set.of();
        String tsquery = String.join(" & ", words.stream().map(w -> w + ":*").toList());
        String match = switch (field) {
            case "summary" -> "to_tsvector('simple', t.summary) @@ to_tsquery('simple', :q)";
            case "description" -> "to_tsvector('simple', coalesce(t.description, '')) @@ to_tsquery('simple', :q)";
            case "comment" -> "exists (select 1 from comments c where c.ticket_id = t.id and c.visibility_role_id is null and c.search @@ to_tsquery('simple', :q))";
            default -> "(t.search @@ to_tsquery('simple', :q) or exists (select 1 from comments c where c.ticket_id = t.id and c.visibility_role_id is null and c.search @@ to_tsquery('simple', :q)))";
        };
        return new HashSet<>(jdbc.sql("select t.id from tickets t where t.space_id in (:spaces) and " + match + " limit 5000")
                .param("spaces", visibleSpaces).param("q", tsquery)
                .query(UUID.class).list());
    }

    private static void dateRange(List<Predicate> p, jakarta.persistence.criteria.CriteriaBuilder cb,
                                  jakarta.persistence.criteria.Path<java.time.Instant> path, LocalDate from, LocalDate to) {
        if (from != null) p.add(cb.greaterThanOrEqualTo(path, from.atStartOfDay().toInstant(ZoneOffset.UTC)));
        if (to != null) p.add(cb.lessThan(path, to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
    }

    /** "priority,desc;updated,asc" → Sort; unknown fields are rejected. Ties broken by key. */
    static Sort sort(String spec) {
        List<Sort.Order> orders = new ArrayList<>();
        if (spec != null && !spec.isBlank()) {
            for (String part : spec.split(";")) {
                String[] fd = part.split(",");
                String path = SORTABLE.get(fd[0].strip());
                if (path == null) throw Errors.badRequest("INVALID_SORT", "Tri impossible sur « " + fd[0] + " »");
                boolean desc = fd.length > 1 && fd[1].strip().equalsIgnoreCase("desc");
                // Priority rank: 1 = most urgent, so "priority desc" (most urgent first) is rank ascending.
                if (fd[0].strip().equals("priority")) desc = !desc;
                orders.add(desc ? Sort.Order.desc(path).nullsLast() : Sort.Order.asc(path).nullsLast());
            }
        }
        orders.add(Sort.Order.asc("space.key"));
        orders.add(Sort.Order.asc("number"));
        return Sort.by(orders);
    }

    private static boolean notEmpty(Collection<?> c) {
        return c != null && !c.isEmpty();
    }
}
