package com.fluxo.field;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.space.Space;
import com.fluxo.ticket.Ticket;
import com.fluxo.ticket.TicketType;
import com.fluxo.user.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Custom fields and field configuration (spec §4.14): the global admin defines fields, a space admin enables them for
 * some ticket types and sets, per space and type, which fields are visible, required at creation, their order and the
 * default of custom fields. Values live in {@code tickets.custom_values} keyed by field id.
 */
@Service
@Transactional
public class FieldService {

    public enum Type { TEXT, TEXTAREA, NUMBER, DATE, SELECT, MULTISELECT, USER, USERS, CHECKBOX, URL }

    public record CustomField(UUID id, String name, Type type, List<String> options) {
        public String ref() { return "cf:" + id; }
    }

    /** Optional system fields a space can hide, require or reorder (summary is always shown and required). */
    public static final List<String> SYSTEM = List.of("description", "assigneeId", "priorityId", "labels", "componentIds",
            "fixVersionIds", "parent", "dueDate", "startDate", "estimate");

    public record Config(UUID ticketTypeId, String field, boolean visible, boolean required, int position, Object defaultValue) {}

    public record Enabled(UUID fieldId, List<UUID> typeIds) {}

    /** One field of the ticket form for a space and type, in display order. */
    public record LayoutField(String field, String name, String kind, boolean visible, boolean required, Object defaultValue,
                              List<String> options) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final PermissionService permissions;
    private final UserRepository users;

    FieldService(JdbcClient jdbc, JsonMapper json, PermissionService permissions, UserRepository users) {
        this.jdbc = jdbc;
        this.json = json;
        this.permissions = permissions;
        this.users = users;
    }

    // --- Definitions (global admin) -------------------------------------------------

    @Transactional(readOnly = true)
    public List<CustomField> all() {
        return jdbc.sql("select id, name, type, options::text as options from custom_fields order by lower(name)").query((rs, i) -> new CustomField(
                rs.getObject("id", UUID.class), rs.getString("name"), Type.valueOf(rs.getString("type")), options(rs.getString("options")))).list();
    }

    @Transactional(readOnly = true)
    public Optional<CustomField> find(UUID id) {
        return all().stream().filter(f -> f.id().equals(id)).findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<CustomField> byName(String name) {
        return all().stream().filter(f -> f.name().equalsIgnoreCase(name)).findFirst();
    }

    public CustomField create(AuthUser me, String name, Type type, List<String> options) {
        admin(me);
        String n = check(name, type, options, null);
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into custom_fields (id, name, type, options) values (?, ?, ?, ?::jsonb)")
                .params(id, n, type.name(), json.writeValueAsString(clean(options))).update();
        return new CustomField(id, n, type, clean(options));
    }

    /** The type never changes once values may exist; name and options can. */
    public CustomField update(AuthUser me, UUID id, String name, List<String> options) {
        admin(me);
        CustomField f = find(id).orElseThrow(() -> Errors.notFound("Champ introuvable"));
        String n = name == null ? f.name() : check(name, f.type(), options == null ? f.options() : options, id);
        List<String> o = options == null ? f.options() : clean(options);
        jdbc.sql("update custom_fields set name = ?, options = ?::jsonb where id = ?").params(n, json.writeValueAsString(o), id).update();
        return new CustomField(id, n, f.type(), o);
    }

    /** Deleting a field also removes its values from every ticket. */
    public void delete(AuthUser me, UUID id) {
        admin(me);
        jdbc.sql("update tickets set custom_values = custom_values - ? where jsonb_exists(custom_values, ?)").params(id.toString(), id.toString()).update();
        jdbc.sql("delete from field_configs where field = ?").param("cf:" + id).update();
        jdbc.sql("delete from custom_fields where id = ?").param(id).update();
    }

    private String check(String name, Type type, List<String> options, UUID self) {
        if (name == null || name.isBlank() || name.strip().length() > 64) throw Errors.badRequest("NAME_REQUIRED", "Nom obligatoire, 64 caractères au plus");
        if (type == null) throw Errors.badRequest("FIELD_TYPE", "Type de champ obligatoire");
        String n = name.strip();
        if (all().stream().anyMatch(f -> f.name().equalsIgnoreCase(n) && !f.id().equals(self))) throw Errors.conflict("NAME_TAKEN", "Le champ « " + n + " » existe déjà");
        if ((type == Type.SELECT || type == Type.MULTISELECT) && clean(options).isEmpty()) throw Errors.badRequest("FIELD_OPTIONS", "Une liste a au moins une option");
        return n;
    }

    private static List<String> clean(List<String> options) {
        return options == null ? List.of() : options.stream().filter(o -> o != null && !o.isBlank()).map(String::strip).distinct().toList();
    }

    private List<String> options(String raw) {
        return Arrays.asList(json.readValue(raw, String[].class));
    }

    private static void admin(AuthUser me) {
        if (!me.admin()) throw Errors.forbidden("Réservé à l'administrateur");
    }

    // --- Space activation and configuration -------------------------------------------

    @Transactional(readOnly = true)
    public List<Enabled> enabled(Space s) {
        return jdbc.sql("select field_id, type_ids::text as type_ids from space_custom_fields where space_id = ?").param(s.getId())
                .query((rs, i) -> new Enabled(rs.getObject("field_id", UUID.class),
                        Arrays.stream(json.readValue(rs.getString("type_ids"), String[].class)).map(UUID::fromString).toList())).list();
    }

    public void setEnabled(AuthUser me, Space s, List<Enabled> list) {
        permissions.check(me, s, Perm.ADMINISTER_SPACE);
        jdbc.sql("delete from space_custom_fields where space_id = ?").param(s.getId()).update();
        for (Enabled e : list) {
            if (find(e.fieldId()).isEmpty()) throw Errors.badRequest("UNKNOWN_FIELD", "Champ inconnu");
            List<UUID> types = e.typeIds() == null ? List.of() : e.typeIds();
            if (!s.getTicketTypes().stream().map(TicketType::getId).toList().containsAll(types)) throw Errors.badRequest("TYPE_REQUIRED", "Type non disponible dans l'espace");
            jdbc.sql("insert into space_custom_fields (space_id, field_id, type_ids) values (?, ?, ?::jsonb)")
                    .params(s.getId(), e.fieldId(), json.writeValueAsString(types)).update();
        }
    }

    /** Custom fields usable on a ticket of this type in this space. */
    @Transactional(readOnly = true)
    public List<CustomField> enabledFor(Space s, TicketType type) {
        Map<UUID, CustomField> defs = new HashMap<>();
        all().forEach(f -> defs.put(f.id(), f));
        return enabled(s).stream().filter(e -> e.typeIds().isEmpty() || e.typeIds().contains(type.getId()))
                .map(e -> defs.get(e.fieldId())).filter(Objects::nonNull).toList();
    }

    @Transactional(readOnly = true)
    public List<Config> configs(Space s) {
        return jdbc.sql("select ticket_type_id, field, visible, required, position, default_value::text as dv from field_configs where space_id = ? order by position")
                .param(s.getId()).query((rs, i) -> new Config(rs.getObject("ticket_type_id", UUID.class), rs.getString("field"),
                        rs.getBoolean("visible"), rs.getBoolean("required"), rs.getInt("position"),
                        rs.getString("dv") == null ? null : json.readValue(rs.getString("dv"), Object.class))).list();
    }

    public void setConfigs(AuthUser me, Space s, List<Config> list) {
        permissions.check(me, s, Perm.ADMINISTER_SPACE);
        jdbc.sql("delete from field_configs where space_id = ?").param(s.getId()).update();
        for (Config c : list) {
            boolean custom = c.field() != null && c.field().startsWith("cf:");
            if (!custom && !SYSTEM.contains(c.field())) throw Errors.badRequest("UNKNOWN_FIELD", "Champ non configurable : " + c.field());
            if (c.ticketTypeId() != null && s.getTicketTypes().stream().noneMatch(t -> t.getId().equals(c.ticketTypeId()))) {
                throw Errors.badRequest("TYPE_REQUIRED", "Type non disponible dans l'espace");
            }
            Object dv = null;
            if (custom && c.defaultValue() != null) {
                CustomField f = find(UUID.fromString(c.field().substring(3))).orElseThrow(() -> Errors.badRequest("UNKNOWN_FIELD", "Champ inconnu"));
                dv = normalize(f, json.valueToTree(c.defaultValue()));
            }
            if (c.required() && !c.visible()) throw Errors.badRequest("FIELD_HIDDEN_REQUIRED", "Un champ obligatoire doit être visible");
            jdbc.sql("insert into field_configs (id, space_id, ticket_type_id, field, visible, required, position, default_value) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb)")
                    .params(UUID.randomUUID(), s.getId(), c.ticketTypeId(), c.field(), c.visible(), c.required(), c.position(),
                            dv == null ? null : json.writeValueAsString(dv)).update();
        }
    }

    /** Fields of the form for this space and type: the type's configuration overrides the space-wide one. */
    @Transactional(readOnly = true)
    public List<LayoutField> layout(Space s, TicketType type) {
        Map<String, Config> conf = new HashMap<>();
        List<Config> all = configs(s);
        all.stream().filter(c -> c.ticketTypeId() == null).forEach(c -> conf.put(c.field(), c));
        all.stream().filter(c -> type.getId().equals(c.ticketTypeId())).forEach(c -> conf.put(c.field(), c));
        List<LayoutField> out = new ArrayList<>();
        Map<String, Integer> order = new HashMap<>();
        int i = 0;
        for (String f : SYSTEM) {
            Config c = conf.get(f);
            order.put(f, c == null ? 1000 + i * 10 : c.position()); // configured fields first, in their order
            out.add(new LayoutField(f, null, "SYSTEM", c == null || c.visible(), c != null && c.required(), null, List.of()));
            i++;
        }
        for (CustomField f : enabledFor(s, type)) {
            Config c = conf.get(f.ref());
            order.put(f.ref(), c == null ? 2000 + i * 10 : c.position());
            out.add(new LayoutField(f.ref(), f.name(), f.type().name(), c == null || c.visible(), c != null && c.required(),
                    c == null ? null : c.defaultValue(), f.options()));
            i++;
        }
        out.sort(Comparator.comparingInt(l -> order.get(l.field())));
        return out;
    }

    // --- Values --------------------------------------------------------------------

    /** Checks a value against the field type; returns the stored value, or null to clear. */
    public Object normalize(CustomField f, JsonNode v) {
        if (v == null || v.isNull() || (v.isString() && v.asString().isBlank()) || (v.isArray() && v.isEmpty())) return null;
        String bad = "Valeur invalide pour « " + f.name() + " »";
        try {
            return switch (f.type()) {
                case TEXT -> limit(v.asString(), 255, f);
                case TEXTAREA -> limit(v.asString(), 10_000, f);
                case NUMBER -> (v.isNumber() ? v.decimalValue() : new BigDecimal(v.asString().replace(',', '.'))).stripTrailingZeros();
                case DATE -> LocalDate.parse(v.asString()).toString();
                case CHECKBOX -> {
                    if (!v.isBoolean()) throw Errors.badRequest("INVALID_FIELD_VALUE", bad);
                    yield v.booleanValue();
                }
                case URL -> {
                    String u = v.asString().strip();
                    if (!u.matches("https?://\\S+")) throw Errors.badRequest("INVALID_FIELD_VALUE", bad + " : URL http(s) attendue");
                    yield limit(u, 2000, f);
                }
                case SELECT -> option(f, v.asString());
                case MULTISELECT -> {
                    if (!v.isArray()) throw Errors.badRequest("INVALID_FIELD_VALUE", bad);
                    List<String> out = new ArrayList<>();
                    v.forEach(x -> out.add(option(f, x.asString())));
                    yield out.stream().distinct().toList();
                }
                case USER -> user(v.asString(), f);
                case USERS -> {
                    if (!v.isArray()) throw Errors.badRequest("INVALID_FIELD_VALUE", bad);
                    List<String> out = new ArrayList<>();
                    v.forEach(x -> out.add(user(x.asString(), f)));
                    yield out.stream().distinct().toList();
                }
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw Errors.badRequest("INVALID_FIELD_VALUE", bad);
        }
    }

    /** Text for history entries. */
    public String display(CustomField f, Object value) {
        if (value == null) return null;
        return switch (f.type()) {
            case USER -> users.findById(UUID.fromString(value.toString())).map(u -> u.getDisplayName()).orElse("?");
            case USERS -> ((List<?>) value).stream().map(id -> users.findById(UUID.fromString(id.toString())).map(u -> u.getDisplayName()).orElse("?"))
                    .reduce((a, b) -> a + ", " + b).orElse("");
            case MULTISELECT -> String.join(", ", ((List<?>) value).stream().map(Object::toString).toList());
            case CHECKBOX -> Boolean.TRUE.equals(value) ? "oui" : "non";
            default -> value.toString();
        };
    }

    /** Required-at-creation check (spec §4.14). */
    public void checkRequired(Ticket t, List<LayoutField> layout) {
        for (LayoutField l : layout) {
            if (!l.required()) continue;
            boolean empty = l.field().startsWith("cf:") ? t.getCustomValues().get(l.field().substring(3)) == null : isEmpty(t, l.field());
            if (empty) throw Errors.badRequest("FIELD_REQUIRED", "Le champ « " + (l.name() != null ? l.name() : l.field()) + " » est obligatoire");
        }
    }

    private static boolean isEmpty(Ticket t, String field) {
        return switch (field) {
            case "description" -> t.getDescription() == null || t.getDescription().isBlank();
            case "assigneeId" -> t.getAssignee() == null;
            case "labels" -> t.getLabels().isEmpty();
            case "componentIds" -> t.getComponents().isEmpty();
            case "fixVersionIds" -> t.getFixVersions().isEmpty();
            case "parent" -> t.getParent() == null;
            case "dueDate" -> t.getDueDate() == null;
            case "startDate" -> t.getStartDate() == null;
            case "estimate" -> t.getEstimate() == null;
            default -> false;
        };
    }

    private static String limit(String s, int max, CustomField f) {
        if (s.length() > max) throw Errors.badRequest("INVALID_FIELD_VALUE", "« " + f.name() + " » : " + max + " caractères au plus");
        return s;
    }

    private static String option(CustomField f, String v) {
        return f.options().stream().filter(o -> o.equalsIgnoreCase(v.strip())).findFirst()
                .orElseThrow(() -> Errors.badRequest("INVALID_FIELD_VALUE", "« " + v + " » n'est pas une option de « " + f.name() + " »"));
    }

    private String user(String id, CustomField f) {
        try {
            UUID u = UUID.fromString(id);
            if (!users.existsById(u)) throw new IllegalArgumentException();
            return u.toString();
        } catch (IllegalArgumentException e) {
            throw Errors.badRequest("INVALID_FIELD_VALUE", "Utilisateur inconnu pour « " + f.name() + " »");
        }
    }

    // --- FQL -------------------------------------------------------------------------

    /** Tickets whose custom field matches (FQL on custom fields by their name). */
    @Transactional(readOnly = true)
    public Set<UUID> matches(CustomField f, String op, List<String> values) {
        String id = f.id().toString();
        boolean multi = f.type() == Type.MULTISELECT || f.type() == Type.USERS;
        String nonEmpty = "jsonb_exists(custom_values, :id) and jsonb_typeof(custom_values -> :id) <> 'null' and custom_values -> :id <> '[]'::jsonb";
        String sql = switch (op) {
            case "is empty", "is not empty" -> "select id from tickets where " + nonEmpty;
            case "=", "in", "!=", "not in" -> multi
                    ? "select id from tickets where " + nonEmpty + " and exists (select 1 from jsonb_array_elements_text(custom_values -> :id) e where lower(e) in (:vals))"
                    : "select id from tickets where lower(custom_values ->> :id) in (:vals)";
            case "~", "!~" -> "select id from tickets where custom_values ->> :id ilike :like";
            case ">", ">=", "<", "<=" -> switch (f.type()) {
                case NUMBER -> "select id from tickets where " + nonEmpty + " and (custom_values ->> :id)::numeric " + op + " :num";
                case DATE -> "select id from tickets where " + nonEmpty + " and (custom_values ->> :id)::date " + op + " :date";
                default -> throw Errors.badRequest("FQL_ERROR", "Opérateur « " + op + " » impossible sur « " + f.name() + " »");
            };
            default -> throw Errors.badRequest("FQL_ERROR", "Opérateur « " + op + " » impossible sur « " + f.name() + " »");
        };
        var q = jdbc.sql(sql).param("id", id);
        if (sql.contains(":vals")) q = q.param("vals", values.isEmpty() ? List.of("") : values.stream().map(v -> v.toLowerCase(Locale.ROOT)).toList());
        if (sql.contains(":like")) q = q.param("like", "%" + (values.isEmpty() ? "" : values.getFirst()) + "%");
        try {
            if (sql.contains(":num")) q = q.param("num", new BigDecimal(values.getFirst().replace(',', '.')));
            if (sql.contains(":date")) q = q.param("date", java.sql.Date.valueOf(LocalDate.parse(values.getFirst())));
        } catch (NumberFormatException | DateTimeParseException e) {
            throw Errors.badRequest("FQL_ERROR", "Valeur invalide pour « " + f.name() + " »");
        }
        return new HashSet<>(q.query(UUID.class).list());
    }
}
