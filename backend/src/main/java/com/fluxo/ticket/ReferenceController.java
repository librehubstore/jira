package com.fluxo.ticket;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.space.Space;
import com.fluxo.space.SpaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/** Global reference data administration (spec V1.6.3): ticket types, priorities, resolutions, link types. */
@RestController
@RequestMapping("/api")
@Transactional
class ReferenceController {

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Set<String> ICONS = Set.of("task", "bug", "story", "epic", "subtask");

    private final JdbcClient jdbc;
    private final SpaceRepository spaces;
    private final TicketTypeRepository types;
    private final PermissionService permissions;

    ReferenceController(JdbcClient jdbc, SpaceRepository spaces, TicketTypeRepository types, PermissionService permissions) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.types = types;
        this.permissions = permissions;
    }

    record TypeRow(UUID id, String name, String icon, String color, int hierarchyLevel, long count) {}
    record PriorityRow(UUID id, String name, int rank, String color, boolean isDefault, long count) {}
    record ResolutionRow(UUID id, String name, boolean isDefault, long count) {}
    record LinkTypeRow(UUID id, String name, String outward, String inward, long count) {}
    record Reference(List<TypeRow> types, List<PriorityRow> priorities, List<ResolutionRow> resolutions, List<LinkTypeRow> linkTypes) {}

    record TypeRequest(String name, String icon, String color, Integer hierarchyLevel) {}
    record PriorityRequest(String name, String color, Boolean isDefault) {}
    record ResolutionRequest(String name, Boolean isDefault) {}
    record LinkTypeRequest(String name, String outward, String inward) {}

    @GetMapping("/admin/reference")
    @Transactional(readOnly = true)
    Reference all(@AuthenticationPrincipal AuthUser me) {
        admin(me);
        return new Reference(
                jdbc.sql("select t.id, t.name, t.icon, t.color, t.hierarchy_level, (select count(*) from tickets x where x.type_id = t.id) n from ticket_types t order by t.hierarchy_level desc, t.name")
                        .query((rs, i) -> new TypeRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("icon"), rs.getString("color"), rs.getInt("hierarchy_level"), rs.getLong("n"))).list(),
                jdbc.sql("select p.id, p.name, p.rank, p.color, p.is_default, (select count(*) from tickets x where x.priority_id = p.id) n from priorities p order by p.rank")
                        .query((rs, i) -> new PriorityRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("rank"), rs.getString("color"), rs.getBoolean("is_default"), rs.getLong("n"))).list(),
                jdbc.sql("select r.id, r.name, r.is_default, (select count(*) from tickets x where x.resolution_id = r.id) n from resolutions r order by r.name")
                        .query((rs, i) -> new ResolutionRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("is_default"), rs.getLong("n"))).list(),
                jdbc.sql("select l.id, l.name, l.outward, l.inward, (select count(*) from ticket_links x where x.link_type_id = l.id) n from link_types l order by l.name")
                        .query((rs, i) -> new LinkTypeRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("outward"), rs.getString("inward"), rs.getLong("n"))).list());
    }

    // --- Ticket types -----------------------------------------------------------------

    @PostMapping("/admin/ticket-types")
    @ResponseStatus(HttpStatus.CREATED)
    void createType(@AuthenticationPrincipal AuthUser me, @RequestBody TypeRequest r) {
        admin(me);
        if (r.hierarchyLevel() == null || r.hierarchyLevel() < -1 || r.hierarchyLevel() > 1) throw Errors.badRequest("INVALID_LEVEL", "Niveau : 1 (epic), 0 (standard) ou -1 (sous-tâche)");
        unique("ticket_types", "name", name(r.name()), null);
        jdbc.sql("insert into ticket_types (id, name, icon, color, hierarchy_level) values (?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), name(r.name()), icon(r.icon()), color(r.color()), r.hierarchyLevel().shortValue()).update();
    }

    @PatchMapping("/admin/ticket-types/{id}")
    void updateType(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody TypeRequest r) {
        admin(me);
        exists("ticket_types", id);
        if (r.name() != null) {
            unique("ticket_types", "name", name(r.name()), id);
            jdbc.sql("update ticket_types set name = ? where id = ?").params(name(r.name()), id).update();
        }
        if (r.icon() != null) jdbc.sql("update ticket_types set icon = ? where id = ?").params(icon(r.icon()), id).update();
        if (r.color() != null) jdbc.sql("update ticket_types set color = ? where id = ?").params(color(r.color()), id).update();
        if (r.hierarchyLevel() != null) throw Errors.badRequest("INVALID_LEVEL", "Le niveau d'un type ne change pas");
    }

    @DeleteMapping("/admin/ticket-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteType(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        admin(me);
        unused("select count(*) from tickets where type_id = ?", id, "Type utilisé par des tickets : changez-les d'abord de type");
        jdbc.sql("delete from space_ticket_types where ticket_type_id = ?").param(id).update();
        jdbc.sql("delete from ticket_types where id = ?").param(id).update();
    }

    /** Types offered in a space (space admin). */
    @PutMapping("/spaces/{key}/ticket-types")
    void spaceTypes(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestBody List<UUID> ids) {
        Space s = spaces.findByKey(key).orElseThrow(() -> Errors.notFound("Espace introuvable"));
        permissions.check(me, s, Perm.ADMINISTER_SPACE);
        List<TicketType> chosen = types.findAllById(ids);
        if (chosen.size() != new HashSet<>(ids).size()) throw Errors.badRequest("TYPE_REQUIRED", "Type inconnu");
        if (chosen.stream().noneMatch(t -> t.getHierarchyLevel() == 0)) throw Errors.badRequest("TYPE_REQUIRED", "Il faut au moins un type standard");
        for (TicketType t : s.getTicketTypes()) {
            if (!chosen.contains(t) && jdbc.sql("select count(*) from tickets where space_id = ? and type_id = ?").params(s.getId(), t.getId()).query(Long.class).optional().orElse(0L) > 0) {
                throw Errors.conflict("TYPE_IN_USE", "Le type « " + t.getName() + " » est utilisé dans cet espace");
            }
        }
        s.getTicketTypes().clear();
        s.getTicketTypes().addAll(chosen);
    }

    // --- Priorities -------------------------------------------------------------------

    @PostMapping("/admin/priorities")
    @ResponseStatus(HttpStatus.CREATED)
    void createPriority(@AuthenticationPrincipal AuthUser me, @RequestBody PriorityRequest r) {
        admin(me);
        unique("priorities", "name", name(r.name()), null);
        Integer last = jdbc.sql("select max(rank) from priorities").query(Integer.class).optional().orElse(null);
        int rank = last == null ? 1 : last + 1;
        jdbc.sql("insert into priorities (id, name, rank, color) values (?, ?, ?, ?)").params(UUID.randomUUID(), name(r.name()), rank, color(r.color())).update();
    }

    @PatchMapping("/admin/priorities/{id}")
    void updatePriority(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody PriorityRequest r) {
        admin(me);
        exists("priorities", id);
        if (r.name() != null) {
            unique("priorities", "name", name(r.name()), id);
            jdbc.sql("update priorities set name = ? where id = ?").params(name(r.name()), id).update();
        }
        if (r.color() != null) jdbc.sql("update priorities set color = ? where id = ?").params(color(r.color()), id).update();
        if (Boolean.TRUE.equals(r.isDefault())) makeDefault("priorities", id);
    }

    /** New order, most urgent first (rank 1 = most urgent). */
    @PutMapping("/admin/priorities/order")
    void orderPriorities(@AuthenticationPrincipal AuthUser me, @RequestBody List<UUID> ids) {
        admin(me);
        long count = jdbc.sql("select count(*) from priorities").query(Long.class).optional().orElse(0L);
        if (ids.size() != count || new HashSet<>(ids).size() != count) throw Errors.badRequest("INVALID_ORDER", "Toutes les priorités, une fois chacune");
        jdbc.sql("update priorities set rank = -rank").update(); // free the unique ranks first
        for (int i = 0; i < ids.size(); i++) jdbc.sql("update priorities set rank = ? where id = ?").params(i + 1, ids.get(i)).update();
    }

    @DeleteMapping("/admin/priorities/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePriority(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestParam(required = false) UUID replaceWith) {
        admin(me);
        replaceAndDelete("priorities", "priority_id", id, replaceWith);
    }

    // --- Resolutions ------------------------------------------------------------------

    @PostMapping("/admin/resolutions")
    @ResponseStatus(HttpStatus.CREATED)
    void createResolution(@AuthenticationPrincipal AuthUser me, @RequestBody ResolutionRequest r) {
        admin(me);
        unique("resolutions", "name", name(r.name()), null);
        jdbc.sql("insert into resolutions (id, name) values (?, ?)").params(UUID.randomUUID(), name(r.name())).update();
    }

    @PatchMapping("/admin/resolutions/{id}")
    void updateResolution(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody ResolutionRequest r) {
        admin(me);
        exists("resolutions", id);
        if (r.name() != null) {
            unique("resolutions", "name", name(r.name()), id);
            jdbc.sql("update resolutions set name = ? where id = ?").params(name(r.name()), id).update();
        }
        if (Boolean.TRUE.equals(r.isDefault())) makeDefault("resolutions", id);
    }

    @DeleteMapping("/admin/resolutions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteResolution(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestParam(required = false) UUID replaceWith) {
        admin(me);
        replaceAndDelete("resolutions", "resolution_id", id, replaceWith);
    }

    // --- Link types -------------------------------------------------------------------

    @PostMapping("/admin/link-types")
    @ResponseStatus(HttpStatus.CREATED)
    void createLinkType(@AuthenticationPrincipal AuthUser me, @RequestBody LinkTypeRequest r) {
        admin(me);
        unique("link_types", "name", name(r.name()), null);
        jdbc.sql("insert into link_types (id, name, outward, inward) values (?, ?, ?, ?)")
                .params(UUID.randomUUID(), name(r.name()), name(r.outward()), name(r.inward())).update();
    }

    @PatchMapping("/admin/link-types/{id}")
    void updateLinkType(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody LinkTypeRequest r) {
        admin(me);
        exists("link_types", id);
        if (r.name() != null) {
            unique("link_types", "name", name(r.name()), id);
            jdbc.sql("update link_types set name = ? where id = ?").params(name(r.name()), id).update();
        }
        if (r.outward() != null) jdbc.sql("update link_types set outward = ? where id = ?").params(name(r.outward()), id).update();
        if (r.inward() != null) jdbc.sql("update link_types set inward = ? where id = ?").params(name(r.inward()), id).update();
    }

    @DeleteMapping("/admin/link-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteLinkType(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        admin(me);
        if (jdbc.sql("select name from link_types where id = ?").param(id).query(String.class).optional().filter("Cloners"::equals).isPresent()) {
            throw Errors.badRequest("SYSTEM_LINK_TYPE", "Le type « clone » sert au clonage");
        }
        unused("select count(*) from ticket_links where link_type_id = ?", id, "Type de lien utilisé : supprimez d'abord ces liens");
        jdbc.sql("delete from link_types where id = ?").param(id).update();
    }

    // --- Helpers ----------------------------------------------------------------------

    /** Tickets using the value move to {@code replaceWith} first; the default value cannot be deleted. */
    private void replaceAndDelete(String table, String column, UUID id, UUID replaceWith) {
        exists(table, id);
        if (jdbc.sql("select is_default from " + table + " where id = ?").param(id).query(Boolean.class).optional().orElse(false)) {
            throw Errors.badRequest("DEFAULT_VALUE", "Choisissez d'abord une autre valeur par défaut");
        }
        long used = jdbc.sql("select count(*) from tickets where " + column + " = ?").param(id).query(Long.class).optional().orElse(0L);
        if (used > 0) {
            if (replaceWith == null || replaceWith.equals(id)) throw Errors.conflict("VALUE_IN_USE", used + " ticket(s) l'utilisent : choisissez une valeur de remplacement");
            exists(table, replaceWith);
            jdbc.sql("update tickets set " + column + " = ?, version = version + 1 where " + column + " = ?").params(replaceWith, id).update();
        }
        jdbc.sql("delete from " + table + " where id = ?").param(id).update();
    }

    private void makeDefault(String table, UUID id) {
        jdbc.sql("update " + table + " set is_default = (id = ?)").param(id).update();
    }

    private void unique(String table, String column, String value, UUID self) {
        long n = jdbc.sql("select count(*) from " + table + " where lower(" + column + ") = lower(?) and id <> ?")
                .params(value, self == null ? new UUID(0, 0) : self).query(Long.class).optional().orElse(0L);
        if (n > 0) throw Errors.conflict("NAME_TAKEN", "« " + value + " » existe déjà");
    }

    private void exists(String table, UUID id) {
        if (jdbc.sql("select count(*) from " + table + " where id = ?").param(id).query(Long.class).optional().orElse(0L) == 0) throw Errors.notFound("Valeur introuvable");
    }

    private void unused(String sql, UUID id, String message) {
        if (jdbc.sql(sql).param(id).query(Long.class).optional().orElse(0L) > 0) throw Errors.conflict("VALUE_IN_USE", message);
    }

    private static String name(String raw) {
        if (raw == null || raw.isBlank() || raw.strip().length() > 64) throw Errors.badRequest("NAME_REQUIRED", "Nom obligatoire, 64 caractères au plus");
        return raw.strip();
    }

    private static String color(String c) {
        if (c == null || !COLOR.matcher(c).matches()) throw Errors.badRequest("INVALID_COLOR", "Couleur attendue au format #rrggbb");
        return c;
    }

    private static String icon(String i) {
        if (i == null || !ICONS.contains(i)) throw Errors.badRequest("INVALID_ICON", "Icône parmi " + ICONS);
        return i;
    }

    private static void admin(AuthUser me) {
        if (!me.admin()) throw Errors.forbidden("Réservé à l'administrateur");
    }
}
