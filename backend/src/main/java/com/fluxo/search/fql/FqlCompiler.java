package com.fluxo.search.fql;

import com.fluxo.auth.AuthUser;
import com.fluxo.collab.Links;
import com.fluxo.search.fql.Fql.*;
import com.fluxo.field.FieldService;
import com.fluxo.ticket.HistoryEntry;
import com.fluxo.ticket.FixVersion;
import com.fluxo.ticket.Ticket;
import com.fluxo.workflow.StatusCategory;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles an FQL AST into a JPA Specification over tickets. Unknown fields, operators or values fail with a
 * positioned {@link Fql.ParseException}, so the UI can underline the faulty part.
 */
public final class FqlCompiler {

    /** What the compiler needs from the application (users, groups, full-text, sets of ids). */
    public interface Context {
        AuthUser me();

        ZoneId zone();

        /** Users matching a username or display name (case-insensitive). */
        List<UUID> users(String nameOrUsername);

        List<UUID> groupMembers(String group);

        /** Tickets matching a full-text query on: summary, description, comment, or text (all). */
        Set<UUID> textMatches(String field, String query);

        Set<UUID> watchedBy(UUID userId);

        /** Rank of a priority by name (1 = most urgent), or null. */
        Integer priorityRank(String name);

        /** Ticket id by key (current, renamed space or moved ticket), or null. */
        UUID ticketId(String key);

        Set<UUID> updatedBy(UUID userId, Instant from, Instant to);

        /** Tickets linked to the given one (either direction), optionally only with this link label. */
        Set<UUID> linkedTo(UUID ticketId, String label);

        /** A custom field by its name, or empty: FQL accepts custom fields by name (spec §4.11). */
        default Optional<FieldService.CustomField> customField(String name) { return Optional.empty(); }

        /** Tickets whose custom field matches {@code op} / {@code values} (non-empty tickets for IS [NOT] EMPTY). */
        default Set<UUID> customMatches(FieldService.CustomField f, String op, List<String> values) { return Set.of(); }
    }

    private static final Pattern RELATIVE = Pattern.compile("([+-]?)\\s*(?:(\\d+)\\s*([wdhm]))(?:\\s*(\\d+)\\s*([wdhm]))?(?:\\s*(\\d+)\\s*([wdhm]))?");
    public static final List<String> FIELDS = List.of("key", "space", "type", "status", "statusCategory", "resolution", "priority",
            "assignee", "reporter", "creator", "labels", "component", "fixVersion", "collaborator", "archived", "parent", "summary", "description", "comment", "text", "created", "updated",
            "resolved", "due", "start", "watcher", "issueLink", "issueLinkType", "hasChildren", "flagged", "estimate");
    public static final List<String> FUNCTIONS = List.of("currentUser()", "membersOf(\"groupe\")", "now()", "startOfDay()", "endOfDay()",
            "startOfWeek()", "endOfWeek()", "startOfMonth()", "endOfMonth()", "startOfYear()", "endOfYear()",
            "linkedTickets(KEY)", "watchedTickets()", "updatedBy(utilisateur)", "releasedVersions()", "unreleasedVersions()");

    private final Context ctx;

    public FqlCompiler(Context ctx) {
        this.ctx = ctx;
    }

    public Specification<Ticket> where(Expr expr) {
        return (root, query, cb) -> expr == null ? cb.conjunction() : predicate(expr, root, query, cb);
    }

    private Predicate predicate(Expr e, Root<Ticket> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        return switch (e) {
            case And a -> cb.and(predicate(a.left(), root, q, cb), predicate(a.right(), root, q, cb));
            case Or o -> cb.or(predicate(o.left(), root, q, cb), predicate(o.right(), root, q, cb));
            case Not n -> cb.not(predicate(n.expr(), root, q, cb));
            case Clause c -> clause(c, root, q, cb);
        };
    }

    // --- Clauses ------------------------------------------------------------------------------------

    private Predicate clause(Clause c, Root<Ticket> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        String field = c.field().replace("_", "");
        return switch (field) {
            case "key", "issue", "ticket" -> keyClause(c, root, cb);
            case "space", "project" -> text(c, cb, root.get("space").get("key"), true);
            case "type", "issuetype" -> text(c, cb, root.get("type").get("name"), false);
            case "status" -> c.op().startsWith("was") || c.op().equals("changed") ? history(c, "status", root, q, cb)
                    : text(c, cb, root.get("status").get("name"), false);
            case "statuscategory" -> category(c, root, cb);
            case "resolution" -> {
                Join<Object, Object> resolution = root.join("resolution", JoinType.LEFT); // keeps unresolved tickets for != / NOT IN
                yield text(c, cb, resolution.get("name"), false, resolution);
            }
            case "priority" -> c.op().startsWith("was") || c.op().equals("changed") ? history(c, "priority", root, q, cb) : priority(c, root, cb);
            case "assignee", "reporter", "creator" -> c.op().startsWith("was") || c.op().equals("changed") ? history(c, field, root, q, cb)
                    : user(c, root, cb, field);
            case "labels", "label" -> named(c, root, q, cb, "labels");
            case "component", "components" -> named(c, root, q, cb, "components");
            case "fixversion", "fixversions" -> named(c, root, q, cb, "fixVersions");
            case "parent" -> parent(c, root, cb);
            case "summary", "description", "comment", "text" -> fullText(c, root, cb, field);
            case "created" -> instant(c, cb, root.get("createdAt"));
            case "updated" -> instant(c, cb, root.get("updatedAt"));
            case "resolved", "resolutiondate" -> instant(c, cb, root.get("resolvedAt"));
            case "due", "duedate" -> date(c, cb, root.get("dueDate"));
            case "start", "startdate" -> date(c, cb, root.get("startDate"));
            case "watcher" -> watcher(c, root, cb);
            case "issuelink" -> issueLink(c, root, q, cb);
            case "issuelinktype" -> issueLinkType(c, root, q, cb);
            case "haschildren" -> hasChildren(c, root, q, cb);
            case "flagged" -> bool(c, cb, root.get("flagged"));
            case "estimate" -> number(c, cb, root.get("estimate"));
            case "archived" -> bool(c, cb, root.get("archived"));
            case "collaborator", "collaborators" -> collaborator(c, root, q, cb);
            default -> {
                FieldService.CustomField f = ctx.customField(c.field()).orElseThrow(() ->
                        new Fql.ParseException("Champ inconnu « " + c.field() + " » (position " + (c.position() + 1) + ")", c.position()));
                yield custom(c, root, cb, f);
            }
        };
    }

    private Predicate keyClause(Clause c, Root<Ticket> root, CriteriaBuilder cb) {
        if (c.op().equals("in") || c.op().equals("not in") || c.op().equals("=") || c.op().equals("!=")) {
            Set<UUID> ids = new HashSet<>();
            for (Value v : c.values()) ids.addAll(keyValue(v));
            Predicate in = ids.isEmpty() ? cb.disjunction() : root.get("id").in(ids);
            return c.op().equals("not in") || c.op().equals("!=") ? cb.not(in) : in;
        }
        if (Set.of("<", "<=", ">", ">=").contains(c.op())) {
            Value v = single(c);
            String[] k = splitKey(v);
            Predicate sameSpace = cb.equal(root.get("space").get("key"), k[0]);
            Expression<Integer> number = root.get("number");
            int n = Integer.parseInt(k[1]);
            return cb.and(sameSpace, switch (c.op()) {
                case "<" -> cb.lt(number, n);
                case "<=" -> cb.le(number, n);
                case ">" -> cb.gt(number, n);
                default -> cb.ge(number, n);
            });
        }
        throw unsupported(c);
    }

    /** A key, or a function returning tickets: linkedTickets(KEY[, "label"]), watchedTickets(), updatedBy(user[, from, to]). */
    private Set<UUID> keyValue(Value v) {
        if (!v.isFunction()) {
            UUID id = ctx.ticketId(v.text().toUpperCase(Locale.ROOT));
            return id == null ? Set.of() : Set.of(id);
        }
        return switch (v.function()) {
            case "watchedtickets", "watchedissues" -> ctx.watchedBy(ctx.me().id());
            case "updatedby" -> {
                if (v.args().isEmpty()) throw new Fql.ParseException("updatedBy(utilisateur) attend un utilisateur", v.position());
                List<UUID> users = ctx.users(v.args().getFirst());
                Instant from = v.args().size() > 1 ? instantOf(new Value(v.args().get(1), null, List.of(), v.position())) : Instant.EPOCH;
                Instant to = v.args().size() > 2 ? instantOf(new Value(v.args().get(2), null, List.of(), v.position())) : Instant.now().plusSeconds(1);
                Set<UUID> ids = new HashSet<>();
                users.forEach(u -> ids.addAll(ctx.updatedBy(u, from, to)));
                yield ids;
            }
            case "linkedtickets", "linkedissues" -> {
                if (v.args().isEmpty()) throw new Fql.ParseException("linkedTickets(CLÉ) attend une clé", v.position());
                UUID id = ctx.ticketId(v.args().getFirst().toUpperCase(Locale.ROOT));
                yield id == null ? Set.of() : ctx.linkedTo(id, v.args().size() > 1 ? v.args().get(1) : null);
            }
            default -> throw new Fql.ParseException("Fonction inconnue « " + v.function() + "() » pour key", v.position());
        };
    }

    private Predicate text(Clause c, CriteriaBuilder cb, Path<String> path, boolean caseSensitiveKey) {
        return text(c, cb, path, caseSensitiveKey, null);
    }

    private Predicate text(Clause c, CriteriaBuilder cb, Path<String> path, boolean upper, Join<?, ?> nullable) {
        Expression<String> expr = upper ? cb.upper(path) : cb.lower(path);
        Function<String, String> norm = s -> upper ? s.toUpperCase(Locale.ROOT) : s.toLowerCase(Locale.ROOT);
        return switch (c.op()) {
            case "=" -> cb.equal(expr, norm.apply(literal(single(c))));
            case "!=" -> cb.or(cb.notEqual(expr, norm.apply(literal(single(c)))), cb.isNull(path));
            case "in" -> expr.in(c.values().stream().map(v -> norm.apply(literal(v))).toList());
            case "not in" -> cb.or(cb.not(expr.in(c.values().stream().map(v -> norm.apply(literal(v))).toList())), cb.isNull(path));
            case "is empty" -> cb.isNull(nullable != null ? nullable : path);
            case "is not empty" -> cb.isNotNull(nullable != null ? nullable : path);
            default -> throw unsupported(c);
        };
    }

    private Predicate category(Clause c, Root<Ticket> root, CriteriaBuilder cb) {
        Path<StatusCategory> path = root.get("status").get("category");
        List<StatusCategory> cats = c.values().stream().map(v -> categoryOf(v)).toList();
        return switch (c.op()) {
            case "=" -> cb.equal(path, cats.getFirst());
            case "!=" -> cb.notEqual(path, cats.getFirst());
            case "in" -> path.in(cats);
            case "not in" -> cb.not(path.in(cats));
            default -> throw unsupported(c);
        };
    }

    private static StatusCategory categoryOf(Value v) {
        String s = v.text() == null ? "" : v.text().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
        return switch (s) {
            case "todo", "àfaire", "afaire", "new" -> StatusCategory.TODO;
            case "inprogress", "encours", "indeterminate" -> StatusCategory.IN_PROGRESS;
            case "done", "terminé", "termine", "complete" -> StatusCategory.DONE;
            default -> throw new Fql.ParseException("Catégorie inconnue « " + v.text() + " » : TODO, IN_PROGRESS ou DONE", v.position());
        };
    }

    private Predicate priority(Clause c, Root<Ticket> root, CriteriaBuilder cb) {
        Path<Integer> rank = root.get("priority").get("rank");
        if (Set.of("<", "<=", ">", ">=").contains(c.op())) {
            Value v = single(c);
            Integer r = ctx.priorityRank(literal(v));
            if (r == null) throw new Fql.ParseException("Priorité inconnue « " + literal(v) + " »", v.position());
            // "priority > Moyenne" = more urgent = smaller rank.
            return switch (c.op()) {
                case ">" -> cb.lt(rank, r);
                case ">=" -> cb.le(rank, r);
                case "<" -> cb.gt(rank, r);
                default -> cb.ge(rank, r);
            };
        }
        return text(c, cb, root.get("priority").get("name"), false);
    }

    private Predicate user(Clause c, Root<Ticket> root, CriteriaBuilder cb, String field) {
        Join<Object, Object> join = root.join(field, field.equals("assignee") ? JoinType.LEFT : JoinType.INNER);
        return switch (c.op()) {
            case "is empty" -> cb.isNull(join);
            case "is not empty" -> cb.isNotNull(join);
            case "=", "in" -> userIn(c, cb, join);
            case "!=", "not in" -> cb.or(cb.not(userIn(c, cb, join)), cb.isNull(join));
            default -> throw unsupported(c);
        };
    }

    private Predicate collaborator(Clause c, Root<Ticket> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        if (c.op().equals("is empty")) return cb.isEmpty(root.get("collaborators"));
        if (c.op().equals("is not empty")) return cb.isNotEmpty(root.get("collaborators"));
        Subquery<UUID> sub = q.subquery(UUID.class);
        Root<Ticket> t = sub.from(Ticket.class);
        Join<Object, Object> u = t.join("collaborators");
        sub.select(t.get("id")).where(userIn(c, cb, u));
        Predicate in = root.get("id").in(sub);
        return switch (c.op()) {
            case "=", "in" -> in;
            case "!=", "not in" -> cb.not(in);
            default -> throw unsupported(c);
        };
    }

    private Predicate userIn(Clause c, CriteriaBuilder cb, Join<Object, Object> join) {
        Set<UUID> ids = new HashSet<>();
        for (Value v : c.values()) ids.addAll(userIds(v));
        return ids.isEmpty() ? cb.disjunction() : join.get("id").in(ids);
    }

    private List<UUID> userIds(Value v) {
        if (v.isFunction()) {
            return switch (v.function()) {
                case "currentuser" -> List.of(ctx.me().id());
                case "membersof" -> {
                    if (v.args().isEmpty()) throw new Fql.ParseException("membersOf(\"groupe\") attend un groupe", v.position());
                    yield ctx.groupMembers(v.args().getFirst());
                }
                default -> throw new Fql.ParseException("Fonction inconnue « " + v.function() + "() » pour un utilisateur", v.position());
            };
        }
        List<UUID> found = ctx.users(v.text());
        if (found.isEmpty()) throw new Fql.ParseException("Utilisateur inconnu « " + v.text() + " »", v.position());
        return found;
    }

    private Predicate custom(Clause c, Root<Ticket> root, CriteriaBuilder cb, FieldService.CustomField f) {
        if (c.op().startsWith("was") || c.op().equals("changed")) throw unsupported(c);
        List<String> values = new ArrayList<>();
        for (Value v : c.values()) {
            if (f.type() == FieldService.Type.USER || f.type() == FieldService.Type.USERS) {
                userIds(v).forEach(id -> values.add(id.toString()));
            } else {
                values.add(literal(v));
            }
        }
        Set<UUID> ids = ctx.customMatches(f, c.op(), values);
        Predicate in = ids.isEmpty() ? cb.disjunction() : root.get("id").in(ids);
        return switch (c.op()) {
            case "is empty", "!=", "not in", "!~" -> cb.not(in);
            default -> in;
        };
    }

    /** Labels, components and fix versions: matched by name (`*` wildcard), versions also by released state. */
    private Predicate named(Clause c, Root<Ticket> root, CriteriaQuery<?> q, CriteriaBuilder cb, String attribute) {
        if (c.op().equals("is empty")) return cb.isEmpty(root.get(attribute));
        if (c.op().equals("is not empty")) return cb.isNotEmpty(root.get(attribute));
        Subquery<UUID> sub = q.subquery(UUID.class);
        Root<Ticket> t = sub.from(Ticket.class);
        Join<Object, Object> l = t.join(attribute);
        List<Predicate> any = new ArrayList<>();
        for (Value v : c.values()) {
            if (v.function() != null && attribute.equals("fixVersions") && Set.of("releasedversions", "unreleasedversions").contains(v.function())) {
                any.add(cb.equal(l.get("status"), v.function().equals("releasedversions") ? FixVersion.Status.RELEASED : FixVersion.Status.UNRELEASED));
                continue;
            }
            String n = literal(v).toLowerCase(Locale.ROOT);
            any.add(n.contains("*") ? cb.like(cb.lower(l.get("name")), n.replace("*", "%")) : cb.equal(cb.lower(l.get("name")), n));
        }
        sub.select(t.get("id")).where(cb.or(any.toArray(Predicate[]::new)));
        Predicate in = root.get("id").in(sub);
        return switch (c.op()) {
            case "=", "in", "~" -> in;
            case "!=", "not in", "!~" -> cb.not(in);
            default -> throw unsupported(c);
        };
    }

    private Predicate parent(Clause c, Root<Ticket> root, CriteriaBuilder cb) {
        Join<Object, Object> parent = root.join("parent", JoinType.LEFT);
        if (c.op().equals("is empty")) return cb.isNull(parent);
        if (c.op().equals("is not empty")) return cb.isNotNull(parent);
        Set<UUID> ids = new HashSet<>();
        for (Value v : c.values()) ids.addAll(keyValue(v));
        Predicate in = ids.isEmpty() ? cb.disjunction() : parent.get("id").in(ids);
        return switch (c.op()) {
            case "=", "in" -> in;
            case "!=", "not in" -> cb.or(cb.not(in), cb.isNull(parent));
            default -> throw unsupported(c);
        };
    }

    private Predicate fullText(Clause c, Root<Ticket> root, CriteriaBuilder cb, String field) {
        if (field.equals("description") && c.op().equals("is empty")) return cb.isNull(root.get("description"));
        if (field.equals("description") && c.op().equals("is not empty")) return cb.isNotNull(root.get("description"));
        if (!c.op().equals("~") && !c.op().equals("!~")) {
            throw new Fql.ParseException("Utilisez ~ ou !~ pour chercher dans « " + c.field() + " » (position " + (c.position() + 1) + ")", c.position());
        }
        Set<UUID> ids = ctx.textMatches(field, literal(single(c)));
        Predicate in = ids.isEmpty() ? cb.disjunction() : root.get("id").in(ids);
        return c.op().equals("~") ? in : cb.not(in);
    }

    private Predicate instant(Clause c, CriteriaBuilder cb, Path<Instant> path) {
        if (c.op().equals("is empty")) return cb.isNull(path);
        if (c.op().equals("is not empty")) return cb.isNotNull(path);
        Value v = single(c);
        Instant at = instantOf(v);
        // A bare date (no time) means the whole day for = and the day's end for <=.
        boolean day = !v.isFunction() && v.text() != null && v.text().matches("\\d{4}[-/]\\d{2}[-/]\\d{2}");
        Instant dayEnd = day ? at.plus(1, ChronoUnit.DAYS) : at;
        return switch (c.op()) {
            case "=" -> day ? cb.and(cb.greaterThanOrEqualTo(path, at), cb.lessThan(path, dayEnd)) : cb.equal(path, at);
            case "!=" -> day ? cb.or(cb.lessThan(path, at), cb.greaterThanOrEqualTo(path, dayEnd)) : cb.notEqual(path, at);
            case ">" -> cb.greaterThanOrEqualTo(path, dayEnd);
            case ">=" -> cb.greaterThanOrEqualTo(path, at);
            case "<" -> cb.lessThan(path, at);
            case "<=" -> cb.lessThan(path, dayEnd.equals(at) ? at.plusMillis(1) : dayEnd);
            default -> throw unsupported(c);
        };
    }

    private Predicate date(Clause c, CriteriaBuilder cb, Path<LocalDate> path) {
        if (c.op().equals("is empty")) return cb.isNull(path);
        if (c.op().equals("is not empty")) return cb.isNotNull(path);
        LocalDate d = LocalDate.ofInstant(instantOf(single(c)), ctx.zone());
        return switch (c.op()) {
            case "=" -> cb.equal(path, d);
            case "!=" -> cb.notEqual(path, d);
            case ">" -> cb.greaterThan(path, d);
            case ">=" -> cb.greaterThanOrEqualTo(path, d);
            case "<" -> cb.lessThan(path, d);
            case "<=" -> cb.lessThanOrEqualTo(path, d);
            default -> throw unsupported(c);
        };
    }

    private Predicate number(Clause c, CriteriaBuilder cb, Path<BigDecimal> path) {
        if (c.op().equals("is empty")) return cb.isNull(path);
        if (c.op().equals("is not empty")) return cb.isNotNull(path);
        BigDecimal n;
        try {
            n = new BigDecimal(literal(single(c)).replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new Fql.ParseException("Nombre attendu", single(c).position());
        }
        return switch (c.op()) {
            case "=" -> cb.equal(path, n);
            case "!=" -> cb.notEqual(path, n);
            case ">" -> cb.gt(path, n);
            case ">=" -> cb.ge(path, n);
            case "<" -> cb.lt(path, n);
            case "<=" -> cb.le(path, n);
            default -> throw unsupported(c);
        };
    }

    private Predicate bool(Clause c, CriteriaBuilder cb, Path<Boolean> path) {
        boolean b = parseBool(single(c));
        return switch (c.op()) {
            case "=" -> b ? cb.isTrue(path) : cb.isFalse(path);
            case "!=" -> b ? cb.isFalse(path) : cb.isTrue(path);
            default -> throw unsupported(c);
        };
    }

    private Predicate hasChildren(Clause c, Root<Ticket> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        Subquery<UUID> sub = q.subquery(UUID.class);
        Root<Ticket> child = sub.from(Ticket.class);
        sub.select(child.get("id")).where(cb.equal(child.get("parent"), root));
        Predicate exists = cb.exists(sub);
        boolean b = parseBool(single(c));
        return (c.op().equals("=") == b) ? exists : cb.not(exists);
    }

    private Predicate watcher(Clause c, Root<Ticket> root, CriteriaBuilder cb) {
        Set<UUID> ids = new HashSet<>();
        for (Value v : c.values()) for (UUID u : userIds(v)) ids.addAll(ctx.watchedBy(u));
        Predicate in = ids.isEmpty() ? cb.disjunction() : root.get("id").in(ids);
        return switch (c.op()) {
            case "=", "in" -> in;
            case "!=", "not in" -> cb.not(in);
            default -> throw unsupported(c);
        };
    }

    /** issueLink = KEY: tickets linked to KEY in either direction (spec §4.11). */
    private Predicate issueLink(Clause c, Root<Ticket> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        Set<UUID> others = new HashSet<>();
        for (Value v : c.values()) others.addAll(keyValue(v));
        if (c.op().equals("is empty") || c.op().equals("is not empty")) {
            Predicate any = cb.exists(linkSub(root, q, cb, null, null));
            return c.op().equals("is empty") ? cb.not(any) : any;
        }
        Predicate linked = others.isEmpty() ? cb.disjunction() : cb.exists(linkSub(root, q, cb, others, null));
        return switch (c.op()) {
            case "=", "in" -> linked;
            case "!=", "not in" -> cb.not(linked);
            default -> throw unsupported(c);
        };
    }

    private Predicate issueLinkType(Clause c, Root<Ticket> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        List<String> labels = c.values().stream().map(v -> literal(v).toLowerCase(Locale.ROOT)).toList();
        Predicate p = cb.exists(linkSub(root, q, cb, null, labels));
        return switch (c.op()) {
            case "=", "in" -> p;
            case "!=", "not in" -> cb.not(p);
            default -> throw unsupported(c);
        };
    }

    private Subquery<UUID> linkSub(Root<Ticket> root, CriteriaQuery<?> q, CriteriaBuilder cb, Set<UUID> others, List<String> labels) {
        Subquery<UUID> sub = q.subquery(UUID.class);
        Root<Links.TicketLink> l = sub.from(Links.TicketLink.class);
        Join<Object, Object> type = l.join("linkType");
        Predicate outward = cb.equal(l.get("source"), root);
        Predicate inward = cb.equal(l.get("target"), root);
        if (others != null) {
            outward = cb.and(outward, l.get("target").get("id").in(others));
            inward = cb.and(inward, l.get("source").get("id").in(others));
        }
        if (labels != null) {
            Expression<String> name = cb.lower(type.get("name"));
            outward = cb.and(outward, cb.or(cb.lower(type.get("outward")).in(labels), name.in(labels)));
            inward = cb.and(inward, cb.or(cb.lower(type.get("inward")).in(labels), name.in(labels)));
        }
        return sub.select(l.get("id")).where(cb.or(outward, inward));
    }

    /**
     * WAS / CHANGED on the change log (spec §4.11). "WAS x": the current value is x, or x appears as an old
     * or new value in the history of that field.
     */
    private Predicate history(Clause c, String field, Root<Ticket> root, CriteriaQuery<?> q, CriteriaBuilder cb) {
        Subquery<UUID> sub = q.subquery(UUID.class);
        Root<HistoryEntry> h = sub.from(HistoryEntry.class);
        List<Predicate> where = new ArrayList<>(List.of(cb.equal(h.get("ticket"), root), cb.equal(h.get("field"), field)));
        if (c.op().equals("changed")) {
            for (ChangeCond cond : c.changes()) {
                switch (cond.kind()) {
                    case "from" -> where.add(cb.equal(cb.lower(h.get("oldDisplay")), displayOf(field, cond.value())));
                    case "to" -> where.add(cb.equal(cb.lower(h.get("newDisplay")), displayOf(field, cond.value())));
                    case "by" -> where.add(h.get("actor").get("id").in(userIds(cond.value())));
                    case "after" -> where.add(cb.greaterThanOrEqualTo(h.get("at"), instantOf(cond.value())));
                    case "before" -> where.add(cb.lessThan(h.get("at"), instantOf(cond.value())));
                    default -> throw new Fql.ParseException("Condition inconnue " + cond.kind(), cond.value().position());
                }
            }
            sub.select(h.get("id")).where(where.toArray(Predicate[]::new));
            return cb.exists(sub);
        }
        List<String> values = c.values().stream().map(v -> displayOf(field, v)).toList();
        where.add(cb.or(cb.lower(h.get("oldDisplay")).in(values), cb.lower(h.get("newDisplay")).in(values)));
        sub.select(h.get("id")).where(where.toArray(Predicate[]::new));
        Expression<String> current = switch (field) {
            case "status" -> cb.lower(root.get("status").get("name"));
            case "priority" -> cb.lower(root.get("priority").get("name"));
            default -> cb.lower(root.join(field, JoinType.LEFT).get("displayName"));
        };
        Predicate was = cb.or(cb.exists(sub), current.in(values));
        return c.op().endsWith("not") || c.op().endsWith("not in") ? cb.not(was) : was;
    }

    /** History stores display values: user display names, status and priority names. */
    private String displayOf(String field, Value v) {
        if (Set.of("assignee", "reporter", "creator").contains(field) && v.isFunction() && v.function().equals("currentuser")) {
            return ctx.me().displayName().toLowerCase(Locale.ROOT);
        }
        return literal(v).toLowerCase(Locale.ROOT);
    }

    // --- Values ---------------------------------------------------------------------------------------

    private static Value single(Clause c) {
        if (c.values().size() != 1) throw unsupported(c);
        return c.values().getFirst();
    }

    private static String literal(Value v) {
        if (v.isFunction()) throw new Fql.ParseException("Fonction « " + v.function() + "() » inattendue ici", v.position());
        return v.text();
    }

    private static boolean parseBool(Value v) {
        String s = literal(v).toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "vrai", "oui", "yes", "1" -> true;
            case "false", "faux", "non", "no", "0" -> false;
            default -> throw new Fql.ParseException("true ou false attendu", v.position());
        };
    }

    private String[] splitKey(Value v) {
        String k = literal(v).toUpperCase(Locale.ROOT);
        int dash = k.lastIndexOf('-');
        if (dash < 1 || !k.substring(dash + 1).matches("\\d+")) throw new Fql.ParseException("Clé invalide « " + k + " »", v.position());
        return new String[]{k.substring(0, dash), k.substring(dash + 1)};
    }

    /** Dates: 2026-10-14, 2026/10/14, 2026-10-14 10:30, -5d, -2w 3d, now(), startOfDay(-1), endOfWeek()… */
    Instant instantOf(Value v) {
        ZoneId zone = ctx.zone();
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (v.isFunction()) {
            int offset = 0;
            if (!v.args().isEmpty()) {
                try {
                    offset = Integer.parseInt(v.args().getFirst().replace("+", "").replaceAll("[dwmy]$", ""));
                } catch (NumberFormatException e) {
                    throw new Fql.ParseException("Décalage invalide « " + v.args().getFirst() + " »", v.position());
                }
            }
            ZonedDateTime day = now.toLocalDate().atStartOfDay(zone);
            return switch (v.function()) {
                case "now" -> now.toInstant();
                case "startofday" -> day.plusDays(offset).toInstant();
                case "endofday" -> day.plusDays(offset + 1).minusNanos(1).toInstant();
                case "startofweek" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset).toInstant();
                case "endofweek" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset + 1).minusNanos(1).toInstant();
                case "startofmonth" -> day.withDayOfMonth(1).plusMonths(offset).toInstant();
                case "endofmonth" -> day.withDayOfMonth(1).plusMonths(offset + 1).minusNanos(1).toInstant();
                case "startofyear" -> day.withDayOfYear(1).plusYears(offset).toInstant();
                case "endofyear" -> day.withDayOfYear(1).plusYears(offset + 1).minusNanos(1).toInstant();
                default -> throw new Fql.ParseException("Fonction de date inconnue « " + v.function() + "() »", v.position());
            };
        }
        String s = v.text().strip();
        try {
            if (s.matches("\\d{4}[-/]\\d{2}[-/]\\d{2}")) return LocalDate.parse(s.replace('/', '-')).atStartOfDay(zone).toInstant();
            if (s.matches("\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}")) {
                return LocalDateTime.parse(s.replace('/', '-').replace(' ', 'T')).atZone(zone).toInstant();
            }
        } catch (DateTimeParseException e) {
            throw new Fql.ParseException("Date invalide « " + s + " »", v.position());
        }
        Matcher m = RELATIVE.matcher(s);
        if (m.matches()) {
            Duration d = Duration.ZERO;
            for (int g = 2; g <= 6; g += 2) {
                if (m.group(g) == null) continue;
                long n = Long.parseLong(m.group(g));
                d = d.plus(switch (m.group(g + 1)) {
                    case "w" -> Duration.ofDays(7 * n);
                    case "d" -> Duration.ofDays(n);
                    case "h" -> Duration.ofHours(n);
                    default -> Duration.ofMinutes(n);
                });
            }
            return "-".equals(m.group(1)) ? now.toInstant().minus(d) : now.toInstant().plus(d);
        }
        throw new Fql.ParseException("Date invalide « " + s + " » (AAAA-MM-JJ, -5d, startOfDay()…)", v.position());
    }

    private static Fql.ParseException unsupported(Clause c) {
        return new Fql.ParseException("Opérateur « " + c.op().toUpperCase(Locale.ROOT) + " » non pris en charge pour « " + c.field() + " » (position "
                + (c.position() + 1) + ")", c.position());
    }

    // --- ORDER BY -------------------------------------------------------------------------------------

    private static final Map<String, String> ORDER = Map.ofEntries(
            Map.entry("key", "number"), Map.entry("summary", "summary"), Map.entry("created", "createdAt"),
            Map.entry("updated", "updatedAt"), Map.entry("due", "dueDate"), Map.entry("duedate", "dueDate"),
            Map.entry("resolved", "resolvedAt"), Map.entry("priority", "priority.rank"), Map.entry("status", "status.name"),
            Map.entry("type", "type.name"), Map.entry("assignee", "assignee.displayName"), Map.entry("reporter", "reporter.displayName"),
            Map.entry("space", "space.key"), Map.entry("rank", "rank"), Map.entry("estimate", "estimate"), Map.entry("start", "startDate"));

    public static Sort sort(List<Fql.Order> orders) {
        List<Sort.Order> out = new ArrayList<>();
        for (Fql.Order o : orders) {
            String path = ORDER.get(o.field().replace("_", ""));
            if (path == null) throw new Fql.ParseException("Tri impossible sur « " + o.field() + " »", 0);
            boolean desc = o.field().equals("priority") ? !o.desc() : o.desc(); // priority DESC = most urgent first
            out.add(desc ? Sort.Order.desc(path).nullsLast() : Sort.Order.asc(path).nullsLast());
        }
        return Sort.by(out);
    }
}
