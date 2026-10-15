package com.fluxo.search;

import com.fluxo.auth.AuthUser;
import com.fluxo.ticket.Label;
import com.fluxo.ticket.Ticket;
import com.fluxo.ticket.TicketViews.*;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/search")
class SearchController {

    private final SearchService service;
    private final JdbcClient jdbc;
    private final com.fluxo.space.PermissionService permissions;

    private final com.fluxo.field.FieldService fields;

    SearchController(SearchService service, JdbcClient jdbc, com.fluxo.space.PermissionService permissions,
                     com.fluxo.field.FieldService fields) {
        this.fields = fields;
        this.service = service;
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    record LastComment(String author, String excerpt, Instant at) {}

    record Row(UUID id, String key, String summary, String spaceKey, TypeRef type, StatusRef status, String resolution,
               PriorityRef priority, UserRef assignee, UserRef reporter, String parentKey, List<String> labels,
               LocalDate dueDate, Instant createdAt, Instant updatedAt, Instant resolvedAt, LastComment lastComment) {}

    record Result(List<Row> rows, long total, int page, int size) {}

    record QuickRow(String key, String summary, TypeRef type, StatusRef status) {}

    record QuickResult(String exactKey, List<QuickRow> results) {}

    @GetMapping
    @Transactional(readOnly = true)
    Result search(@AuthenticationPrincipal AuthUser me, @ModelAttribute SearchService.Criteria criteria,
                  @RequestParam(required = false) String sort,
                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        Page<Ticket> p = service.search(me, criteria, sort, page, Math.min(size, 200));
        return new Result(rows(p.getContent()), p.getTotalElements(), p.getNumber(), p.getSize());
    }

    record FqlMeta(List<String> fields, List<String> operators, List<String> functions, List<String> keywords) {}

    /** Vocabulary for the FQL editor's autocompletion (spec §4.11). */
    @GetMapping("/fql/meta")
    FqlMeta fqlMeta() {
        List<String> all = new java.util.ArrayList<>(com.fluxo.search.fql.FqlCompiler.FIELDS);
        // Custom fields by name, quoted when needed.
        fields.all().forEach(f -> all.add(f.name().matches("[\\p{L}\\w-]+") ? f.name() : "\"" + f.name() + "\""));
        return new FqlMeta(all,
                List.of("=", "!=", ">", ">=", "<", "<=", "~", "!~", "IN", "NOT IN", "IS EMPTY", "IS NOT EMPTY", "WAS", "WAS IN", "WAS NOT", "CHANGED"),
                com.fluxo.search.fql.FqlCompiler.FUNCTIONS,
                List.of("AND", "OR", "NOT", "ORDER BY", "ASC", "DESC", "EMPTY", "FROM", "TO", "BY", "AFTER", "BEFORE"));
    }

    /** Known values of a field, restricted to what the user can see (statuses and labels of visible spaces…). */
    @GetMapping("/fql/values")
    @Transactional(readOnly = true)
    List<String> fqlValues(@AuthenticationPrincipal AuthUser me, @RequestParam String field, @RequestParam(defaultValue = "") String q) {
        Set<UUID> spaces = permissions.visibleSpaces(me).keySet();
        if (spaces.isEmpty()) return List.of();
        String sql = switch (field.toLowerCase(Locale.ROOT)) {
            case "status" -> "select distinct st.name from statuses st join spaces s on s.workflow_id = st.workflow_id where s.id in (:spaces)";
            case "type", "issuetype" -> "select name from ticket_types";
            case "priority" -> "select name from priorities order by rank";
            case "resolution" -> "select name from resolutions";
            case "statuscategory" -> "select unnest(array['TODO','IN_PROGRESS','DONE'])";
            case "space", "project" -> "select key from spaces where id in (:spaces)";
            case "labels", "label" -> "select distinct name from labels where space_id in (:spaces)";
            case "assignee", "reporter", "creator", "watcher" -> "select username from users where active";
            case "issuelinktype" -> "select outward from link_types union select inward from link_types";
            case "flagged", "haschildren" -> "select unnest(array['true','false'])";
            default -> null;
        };
        if (sql == null) {
            // Options of a list custom field.
            return fields.byName(field.replace("\"", "")).map(f -> f.options().stream()
                    .filter(o -> o.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))).limit(30).toList()).orElse(List.of());
        }
        var stmt = jdbc.sql(sql);
        if (sql.contains(":spaces")) stmt = stmt.param("spaces", spaces);
        String needle = q.toLowerCase(Locale.ROOT);
        return stmt.query(String.class).list().stream().filter(v -> v.toLowerCase(Locale.ROOT).contains(needle)).sorted().limit(30).toList();
    }

    @GetMapping("/quick")
    @Transactional(readOnly = true)
    QuickResult quick(@AuthenticationPrincipal AuthUser me, @RequestParam String q) {
        var r = service.quick(me, q);
        return new QuickResult(r.exactKey(), r.results().stream()
                .map(t -> new QuickRow(t.getKey(), t.getSummary(), TypeRef.of(t.getType()), StatusRef.of(t.getStatus()))).toList());
    }

    /** Keys of the current search, at most 1000 (« tout sélectionner » for bulk changes, spec §4.12). */
    @GetMapping("/keys")
    @Transactional(readOnly = true)
    List<String> keys(@AuthenticationPrincipal AuthUser me, @ModelAttribute SearchService.Criteria criteria,
                      @RequestParam(required = false) String sort) {
        return service.search(me, criteria, sort, 0, 1000).map(Ticket::getKey).getContent();
    }

    /** CSV of the current search (spec §4.11), UTF-8 with BOM so spreadsheets read accents correctly. */
    @GetMapping("/export.csv")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthUser me, @ModelAttribute SearchService.Criteria criteria,
                                  @RequestParam(required = false) String sort) {
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("Clé;Type;Résumé;Statut;Résolution;Priorité;Assigné;Rapporteur;Parent;Labels;Échéance;Créé;Mis à jour;Résolu\r\n");
        for (Row r : rows(service.export(me, criteria, sort))) {
            csv.append(String.join(";", List.of(r.key(), r.type().name(), r.summary(), r.status().name(), str(r.resolution()),
                    r.priority().name(), r.assignee() == null ? "" : r.assignee().displayName(), r.reporter().displayName(),
                    str(r.parentKey()), String.join(" ", r.labels()), str(r.dueDate()), str(r.createdAt()), str(r.updatedAt()),
                    str(r.resolvedAt())).stream().map(SearchController::cell).toList())).append("\r\n");
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("tickets.csv").build().toString())
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<Row> rows(List<Ticket> tickets) {
        Map<UUID, LastComment> last = lastComments(tickets.stream().map(Ticket::getId).toList());
        return tickets.stream().map(t -> new Row(t.getId(), t.getKey(), t.getSummary(), t.getSpace().getKey(),
                TypeRef.of(t.getType()), StatusRef.of(t.getStatus()), t.getResolution() == null ? null : t.getResolution().getName(),
                PriorityRef.of(t.getPriority()), UserRef.of(t.getAssignee()), UserRef.of(t.getReporter()),
                t.getParent() == null ? null : t.getParent().getKey(),
                t.getLabels().stream().map(Label::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                t.getDueDate(), t.getCreatedAt(), t.getUpdatedAt(), t.getResolvedAt(), last.get(t.getId()))).toList();
    }

    /** Most recent comment per ticket (spec §4.11 "dernier commentaire" column). */
    private Map<UUID, LastComment> lastComments(List<UUID> ids) {
        Map<UUID, LastComment> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        jdbc.sql("""
                        select distinct on (c.ticket_id) c.ticket_id, u.display_name, left(c.body, 140) as excerpt, c.created_at
                        from comments c join users u on u.id = c.author_id
                        where c.ticket_id in (:ids) order by c.ticket_id, c.created_at desc
                        """).param("ids", ids)
                .query((rs, i) -> Map.entry(rs.getObject("ticket_id", UUID.class),
                        new LastComment(rs.getString("display_name"), rs.getString("excerpt"), rs.getTimestamp("created_at").toInstant())))
                .list().forEach(e -> m.put(e.getKey(), e.getValue()));
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** CSV-escapes a cell and neutralises spreadsheet formulas (=, +, -, @). */
    static String cell(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
