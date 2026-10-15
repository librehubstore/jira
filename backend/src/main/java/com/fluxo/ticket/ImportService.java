package com.fluxo.ticket;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.space.Space;
import com.fluxo.space.SpaceService;
import com.fluxo.user.UserRepository;
import com.fluxo.workflow.Status;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSV import (spec §8): columns mapped to fields, preview of the first rows, simulation mode, explicit estimation unit,
 * error report per line. Each row is its own transaction; a simulation rolls every row back.
 */
@Service
public class ImportService {

    public static final Set<String> FIELDS = Set.of("id", "summary", "description", "type", "priority", "assignee", "reporter",
            "labels", "components", "fixVersions", "dueDate", "startDate", "estimate", "parent", "status");
    static final int MAX_ROWS = 5000;
    private static final Pattern ESTIMATE = Pattern.compile("(\\d+(?:[.,]\\d+)?) ?(h|m|pts?)?", Pattern.CASE_INSENSITIVE);

    public record Preview(List<String> headers, List<List<String>> rows, int total, char delimiter) {}

    /** {@code mapping}: column index → field; {@code estimateUnit}: unit of bare numbers (h, m, pts). */
    public record Options(Map<Integer, String> mapping, String estimateUnit, Boolean dryRun) {}

    public record LineError(int line, String message) {}

    public record Report(boolean dryRun, int total, List<String> created, List<LineError> errors) {}

    private final TicketService tickets;
    private final PermissionService permissions;
    private final UserRepository users;
    private final ComponentRepository components;
    private final FixVersionRepository versions;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final com.fluxo.space.SpaceRepository spaces;

    ImportService(TicketService tickets, PermissionService permissions, UserRepository users, ComponentRepository components,
                  FixVersionRepository versions, PlatformTransactionManager txManager, JsonMapper json,
                  com.fluxo.space.SpaceRepository spaces) {
        this.spaces = spaces;
        this.tickets = tickets;
        this.permissions = permissions;
        this.users = users;
        this.components = components;
        this.versions = versions;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
    }

    public Preview preview(String csv) {
        List<List<String>> rows = parse(csv);
        if (rows.isEmpty()) throw Errors.badRequest("EMPTY_CSV", "Fichier CSV vide");
        return new Preview(rows.getFirst(), rows.subList(1, Math.min(rows.size(), 11)), rows.size() - 1, delimiter(csv));
    }

    public Report run(AuthUser me, Space space, String csv, Options o) {
        permissions.check(me, space, Perm.CREATE);
        permissions.check(me, space, Perm.BULK_CHANGE);
        List<List<String>> rows = parse(csv);
        if (rows.size() < 2) throw Errors.badRequest("EMPTY_CSV", "Aucune ligne à importer");
        if (rows.size() - 1 > MAX_ROWS) throw Errors.badRequest("CSV_TOO_LARGE", "Au plus " + MAX_ROWS + " lignes");
        Map<Integer, String> mapping = o.mapping() == null ? Map.of() : o.mapping();
        if (!FIELDS.containsAll(mapping.values())) throw Errors.badRequest("UNKNOWN_FIELD", "Champs possibles : " + FIELDS);
        if (!mapping.containsValue("summary")) throw Errors.badRequest("SUMMARY_REQUIRED", "Associez une colonne au résumé");
        boolean dry = Boolean.TRUE.equals(o.dryRun());
        String spaceUnit = SpaceService.estimateUnit(space);
        Map<String, String> keyByRowId = new HashMap<>();
        Set<String> rowIds = new HashSet<>();
        List<String> created = new ArrayList<>();
        List<LineError> errors = new ArrayList<>();
        int idCol = mapping.entrySet().stream().filter(e -> e.getValue().equals("id")).map(Map.Entry::getKey).findFirst().orElse(-1);
        if (idCol >= 0) rows.stream().skip(1).forEach(r -> rowIds.add(cell(r, idCol)));
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            int line = i + 1;
            try {
                String key = tx.execute(status -> {
                    Space attached = spaces.findById(space.getId()).orElseThrow(); // each row runs in its own transaction
                    String k = importRow(me, attached, row, mapping, o.estimateUnit(), spaceUnit, keyByRowId, rowIds, dry);
                    if (dry) status.setRollbackOnly();
                    return k;
                });
                created.add(key);
                // A simulated row is rolled back: its key must not serve as a parent.
                if (!dry && idCol >= 0 && !cell(row, idCol).isEmpty()) keyByRowId.put(cell(row, idCol), key);
            } catch (ErrorResponseException e) {
                errors.add(new LineError(line, e.getBody().getDetail()));
            } catch (RuntimeException e) {
                errors.add(new LineError(line, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return new Report(dry, rows.size() - 1, dry ? List.of() : created, errors);
    }

    private String importRow(AuthUser me, Space space, List<String> row, Map<Integer, String> mapping, String unit, String spaceUnit,
                             Map<String, String> keyByRowId, Set<String> rowIds, boolean dry) {
        ObjectNode body = json.createObjectNode();
        String statusName = null;
        for (var m : mapping.entrySet()) {
            String v = cell(row, m.getKey());
            if (v.isEmpty()) continue;
            switch (m.getValue()) {
                case "summary" -> body.put("summary", String.join(" ", v.lines().map(String::strip).filter(l -> !l.isEmpty()).toList())); // a summary is one line
                case "description" -> body.put("description", v);
                case "type" -> body.put("typeId", space.getTicketTypes().stream().filter(t -> t.getName().equalsIgnoreCase(v)).findFirst()
                        .orElseThrow(() -> Errors.badRequest("TYPE_REQUIRED", "Type inconnu dans l'espace : " + v)).getId().toString());
                case "priority" -> body.put("priorityId", tickets.priorityByName(v)
                        .orElseThrow(() -> Errors.badRequest("INVALID_PRIORITY", "Priorité inconnue : " + v)).toString());
                case "assignee" -> body.put("assigneeId", user(v));
                case "reporter" -> body.put("reporterId", user(v));
                case "labels" -> body.set("labels", array(v));
                case "components" -> body.set("componentIds", ids(v, n -> components.findByName(space.getId(), n).map(Component::getId), "Composant inconnu : "));
                case "fixVersions" -> body.set("fixVersionIds", ids(v, n -> versions.findByName(space.getId(), n).map(FixVersion::getId), "Version inconnue : "));
                case "dueDate" -> body.put("dueDate", v);
                case "startDate" -> body.put("startDate", v);
                case "estimate" -> body.put("estimate", estimate(v, unit, spaceUnit));
                case "parent" -> {
                    String key = keyByRowId.get(v);
                    if (key != null) body.put("parent", key);
                    else if (rowIds.contains(v)) {
                        if (!dry) throw Errors.badRequest("INVALID_PARENT", "Parent « " + v + " » non importé (ligne en erreur ou plus bas dans le fichier)");
                    } else body.put("parent", v); // an existing ticket key
                }
                case "status" -> statusName = v;
                default -> { }
            }
        }
        Ticket t = tickets.create(me, space, body);
        if (statusName != null && !statusName.equalsIgnoreCase(t.getStatus().getName())) {
            String name = statusName;
            Status target = space.workflowFor(t.getType()).getStatuses().stream().filter(s -> s.getName().equalsIgnoreCase(name)).findFirst()
                    .orElseThrow(() -> Errors.badRequest("INVALID_STATUS", "Statut inconnu : " + name));
            tickets.transition(me, t.getKey(), target.getId(), null, null);
        }
        return t.getKey();
    }

    /** Explicit units (spec §8 [Fix]): "2h", "90m", "3pts"; bare numbers use the unit chosen for the column. */
    static BigDecimal estimate(String raw, String defaultUnit, String spaceUnit) {
        Matcher m = ESTIMATE.matcher(raw.strip());
        if (!m.matches()) throw Errors.badRequest("INVALID_ESTIMATE", "Estimation illisible : « " + raw + " »");
        BigDecimal n = new BigDecimal(m.group(1).replace(',', '.'));
        String unit = (m.group(2) == null ? defaultUnit == null ? "" : defaultUnit : m.group(2)).toLowerCase(Locale.ROOT);
        if (unit.isEmpty()) throw Errors.badRequest("ESTIMATE_UNIT", "Unité d'estimation manquante : précisez h, m ou pts");
        boolean points = unit.startsWith("pt");
        if (points != spaceUnit.equals("POINTS")) {
            throw Errors.badRequest("ESTIMATE_UNIT", "« " + raw + " » : l'espace compte en " + (spaceUnit.equals("POINTS") ? "points" : "heures"));
        }
        return unit.equals("m") ? n.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP).stripTrailingZeros() : n;
    }

    private String user(String v) {
        return users.findByUsernameIgnoreCase(v).orElseThrow(() -> Errors.badRequest("UNKNOWN_USER", "Utilisateur inconnu : " + v)).getId().toString();
    }

    private ArrayNode array(String v) {
        ArrayNode a = json.createArrayNode();
        Arrays.stream(v.split("[\\s,]+")).filter(x -> !x.isBlank()).forEach(a::add);
        return a;
    }

    private ArrayNode ids(String v, java.util.function.Function<String, Optional<UUID>> find, String error) {
        ArrayNode a = json.createArrayNode();
        for (String n : v.split(",")) {
            if (n.isBlank()) continue;
            a.add(find.apply(n.strip()).orElseThrow(() -> Errors.badRequest("UNKNOWN_VALUE", error + n.strip())).toString());
        }
        return a;
    }

    private static String cell(List<String> row, int i) {
        return i >= 0 && i < row.size() ? row.get(i).strip() : "";
    }

    // --- CSV -------------------------------------------------------------------------

    /** The delimiter used most in the header line among ; , and tab. */
    static char delimiter(String csv) {
        String header = csv.lines().findFirst().orElse("");
        char best = ',';
        long max = -1;
        for (char c : new char[]{';', ',', '\t'}) {
            long n = header.chars().filter(x -> x == c).count();
            if (n > max) { max = n; best = c; }
        }
        return best;
    }

    /** RFC 4180: quoted fields, doubled quotes, line breaks inside quotes; a UTF-8 BOM is ignored. */
    static List<List<String>> parse(String csv) {
        if (csv.startsWith("﻿")) csv = csv.substring(1);
        char d = delimiter(csv);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cell.append(c);
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == d) {
                row.add(cell.toString()); cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                if (!(row.size() == 1 && row.getFirst().isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }
}
