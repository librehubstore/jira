package com.fluxo.ticket;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.space.Space;
import com.fluxo.space.SpaceRepository;
import com.fluxo.ticket.TicketViews.UserRef;
import com.fluxo.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Space catalogs (spec §4.13): labels (rename, merge, colour, delete), components, versions (release, archive, merge). */
@RestController
@RequestMapping("/api")
class CatalogController {

    private static final Pattern LABEL = Pattern.compile("[^\\s,]{1,64}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    private final LabelRepository labels;
    private final ComponentRepository components;
    private final FixVersionRepository versions;
    private final SpaceRepository spaces;
    private final UserRepository users;
    private final PermissionService permissions;
    private final JdbcClient jdbc;
    private final Clock clock;

    CatalogController(LabelRepository labels, ComponentRepository components, FixVersionRepository versions, SpaceRepository spaces,
                      UserRepository users, PermissionService permissions, JdbcClient jdbc, Clock clock) {
        this.labels = labels;
        this.components = components;
        this.versions = versions;
        this.spaces = spaces;
        this.users = users;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    record LabelView(UUID id, String name, String color, long count) {}

    record LabelPatch(String name, String color) {}

    record MergeRequest(UUID targetId) {}

    record ComponentView(UUID id, String name, String description, UserRef lead, boolean archived, long count) {}

    record ComponentRequest(String name, String description, String leadId, Boolean archived) {}

    record VersionView(UUID id, String name, String description, LocalDate startDate, LocalDate releaseDate,
                       FixVersion.Status status, long count, long done) {}

    record VersionRequest(String name, String description, LocalDate startDate, LocalDate releaseDate) {}

    record ReleaseRequest(LocalDate releaseDate) {}

    // --- Labels ----------------------------------------------------------------------

    /** Management list with usage counts (the plain `/labels` endpoint serves autocompletion). */
    @GetMapping("/spaces/{key}/label-catalog")
    @Transactional(readOnly = true)
    List<LabelView> labels(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        Space s = space(me, key, Perm.BROWSE);
        Map<UUID, Long> counts = counts("select label_id as id, count(*) as n from ticket_labels tl join labels l on l.id = tl.label_id where l.space_id = ? group by label_id", s);
        return jdbc.sql("select id, name, color from labels where space_id = ? order by lower(name)").param(s.getId())
                .query((rs, i) -> new LabelView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("color"),
                        counts.getOrDefault(rs.getObject("id", UUID.class), 0L))).list();
    }

    /** Renaming applies to every ticket at once; a name already taken must be merged instead. */
    @PatchMapping("/labels/{id}")
    @Transactional
    LabelView patchLabel(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody LabelPatch req) {
        Label l = label(me, id);
        if (req.name() != null && !req.name().strip().equals(l.getName())) {
            String name = req.name().strip();
            if (!LABEL.matcher(name).matches()) throw Errors.badRequest("INVALID_LABEL", "Label invalide : sans espace, 64 caractères max.");
            labels.findByName(l.getSpace().getId(), name).filter(o -> !o.getId().equals(id)).ifPresent(o -> {
                throw Errors.conflict("LABEL_EXISTS", "Le label « " + o.getName() + " » existe déjà : fusionnez-les");
            });
            l.setName(name);
        }
        if (req.color() != null) {
            if (!req.color().isEmpty() && !COLOR.matcher(req.color()).matches()) throw Errors.badRequest("INVALID_COLOR", "Couleur attendue au format #rrggbb");
            l.setColor(req.color().isEmpty() ? null : req.color());
        }
        return new LabelView(l.getId(), l.getName(), l.getColor(), 0);
    }

    @PostMapping("/labels/{id}/merge")
    @Transactional
    void mergeLabel(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody MergeRequest req) {
        Label source = label(me, id), target = label(me, req.targetId());
        if (source.equals(target) || !source.getSpace().equals(target.getSpace())) throw Errors.badRequest("INVALID_MERGE", "Choisissez un autre label du même espace");
        jdbc.sql("insert into ticket_labels (ticket_id, label_id) select ticket_id, ? from ticket_labels where label_id = ? on conflict do nothing")
                .params(target.getId(), source.getId()).update();
        labels.delete(source); // its ticket_labels rows go with it
    }

    @DeleteMapping("/labels/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteLabel(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        labels.delete(label(me, id));
    }

    private Label label(AuthUser me, UUID id) {
        Label l = labels.findById(id).orElseThrow(() -> Errors.notFound("Label introuvable"));
        permissions.check(me, l.getSpace(), Perm.ADMINISTER_SPACE);
        return l;
    }

    // --- Components ------------------------------------------------------------------

    @GetMapping("/spaces/{key}/components")
    @Transactional(readOnly = true)
    List<ComponentView> components(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        Space s = space(me, key, Perm.BROWSE);
        Map<UUID, Long> counts = counts("select component_id as id, count(*) as n from ticket_components tc join components c on c.id = tc.component_id where c.space_id = ? group by component_id", s);
        return components.findBySpaceIdOrderByNameAsc(s.getId()).stream().map(c -> view(c, counts.getOrDefault(c.getId(), 0L))).toList();
    }

    @PostMapping("/spaces/{key}/components")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    ComponentView createComponent(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestBody ComponentRequest req) {
        Space s = space(me, key, Perm.ADMINISTER_SPACE);
        Component c = new Component(s, uniqueName(req.name(), components.findByName(s.getId(), name(req.name())).isPresent()));
        fill(c, req);
        return view(components.save(c), 0);
    }

    @PatchMapping("/components/{id}")
    @Transactional
    ComponentView updateComponent(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody ComponentRequest req) {
        Component c = component(me, id);
        if (req.name() != null && !name(req.name()).equalsIgnoreCase(c.getName())) {
            c.setName(uniqueName(req.name(), components.findByName(c.getSpace().getId(), name(req.name())).isPresent()));
        } else if (req.name() != null) {
            c.setName(name(req.name()));
        }
        fill(c, req);
        return view(c, 0);
    }

    @DeleteMapping("/components/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteComponent(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        components.delete(component(me, id));
    }

    private void fill(Component c, ComponentRequest req) {
        if (req.description() != null) c.setDescription(req.description().isBlank() ? null : req.description());
        if (req.leadId() != null) {
            if (req.leadId().isBlank()) c.setLead(null);
            else {
                UUID lead = UUID.fromString(req.leadId());
                if (!permissions.isAssignable(c.getSpace(), lead)) throw Errors.badRequest("INVALID_LEAD", "Le responsable doit être assignable dans l'espace");
                c.setLead(users.getReferenceById(lead));
            }
        }
        if (req.archived() != null) c.setArchived(req.archived());
    }

    private Component component(AuthUser me, UUID id) {
        Component c = components.findById(id).orElseThrow(() -> Errors.notFound("Composant introuvable"));
        permissions.check(me, c.getSpace(), Perm.ADMINISTER_SPACE);
        return c;
    }

    private static ComponentView view(Component c, long count) {
        return new ComponentView(c.getId(), c.getName(), c.getDescription(), UserRef.of(c.getLead()), c.isArchived(), count);
    }

    // --- Versions --------------------------------------------------------------------

    @GetMapping("/spaces/{key}/versions")
    @Transactional(readOnly = true)
    List<VersionView> versions(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        Space s = space(me, key, Perm.BROWSE);
        Map<UUID, long[]> stats = new HashMap<>();
        jdbc.sql("""
                select tv.version_id as id, count(*) as n, count(*) filter (where st.category = 'DONE') as done
                from ticket_fix_versions tv join tickets t on t.id = tv.ticket_id join statuses st on st.id = t.status_id
                where t.space_id = ? group by tv.version_id
                """).param(s.getId()).query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), new long[]{rs.getLong("n"), rs.getLong("done")}))
                .list().forEach(e -> stats.put(e.getKey(), e.getValue()));
        return versions.findForSpace(s.getId()).stream().map(v -> view(v, stats.getOrDefault(v.getId(), new long[2]))).toList();
    }

    @PostMapping("/spaces/{key}/versions")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    VersionView createVersion(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestBody VersionRequest req) {
        Space s = space(me, key, Perm.MANAGE_VERSIONS);
        FixVersion v = new FixVersion(s, uniqueName(req.name(), versions.findByName(s.getId(), name(req.name())).isPresent()));
        fill(v, req);
        return view(versions.save(v), new long[2]);
    }

    @PatchMapping("/versions/{id}")
    @Transactional
    VersionView updateVersion(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody VersionRequest req) {
        FixVersion v = version(me, id);
        if (req.name() != null && !name(req.name()).equalsIgnoreCase(v.getName())) {
            v.setName(uniqueName(req.name(), versions.findByName(v.getSpace().getId(), name(req.name())).isPresent()));
        }
        fill(v, req);
        return view(v, new long[2]);
    }

    @PostMapping("/versions/{id}/release")
    @Transactional
    VersionView release(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody(required = false) ReleaseRequest req) {
        FixVersion v = version(me, id);
        v.setStatus(FixVersion.Status.RELEASED);
        v.setReleaseDate(req != null && req.releaseDate() != null ? req.releaseDate() : LocalDate.now(clock));
        return view(v, new long[2]);
    }

    @PostMapping("/versions/{id}/unrelease")
    @Transactional
    VersionView unrelease(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        FixVersion v = version(me, id);
        v.setStatus(FixVersion.Status.UNRELEASED);
        return view(v, new long[2]);
    }

    @PostMapping("/versions/{id}/archive")
    @Transactional
    VersionView archive(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        FixVersion v = version(me, id);
        v.setStatus(FixVersion.Status.ARCHIVED);
        return view(v, new long[2]);
    }

    /** Tickets of the source version move to the target, then the source is deleted. */
    @PostMapping("/versions/{id}/merge")
    @Transactional
    void mergeVersion(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody MergeRequest req) {
        FixVersion source = version(me, id), target = version(me, req.targetId());
        if (source.equals(target) || !source.getSpace().equals(target.getSpace())) throw Errors.badRequest("INVALID_MERGE", "Choisissez une autre version du même espace");
        jdbc.sql("insert into ticket_fix_versions (ticket_id, version_id) select ticket_id, ? from ticket_fix_versions where version_id = ? on conflict do nothing")
                .params(target.getId(), source.getId()).update();
        versions.delete(source);
    }

    @DeleteMapping("/versions/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteVersion(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        versions.delete(version(me, id));
    }

    private void fill(FixVersion v, VersionRequest req) {
        if (req.description() != null) v.setDescription(req.description().isBlank() ? null : req.description());
        if (req.startDate() != null) v.setStartDate(req.startDate());
        if (req.releaseDate() != null) v.setReleaseDate(req.releaseDate());
        if (v.getStartDate() != null && v.getReleaseDate() != null && v.getStartDate().isAfter(v.getReleaseDate())) {
            throw Errors.badRequest("INVALID_DATES", "La date de début précède la date de sortie");
        }
    }

    private FixVersion version(AuthUser me, UUID id) {
        FixVersion v = versions.findById(id).orElseThrow(() -> Errors.notFound("Version introuvable"));
        permissions.check(me, v.getSpace(), Perm.MANAGE_VERSIONS);
        return v;
    }

    private static VersionView view(FixVersion v, long[] stats) {
        return new VersionView(v.getId(), v.getName(), v.getDescription(), v.getStartDate(), v.getReleaseDate(), v.getStatus(), stats[0], stats[1]);
    }

    // --- Helpers ---------------------------------------------------------------------

    private static String name(String raw) {
        if (raw == null || raw.isBlank()) throw Errors.badRequest("NAME_REQUIRED", "Le nom est obligatoire");
        String n = raw.strip();
        if (n.length() > 64) throw Errors.badRequest("NAME_TOO_LONG", "64 caractères au plus");
        return n;
    }

    private static String uniqueName(String raw, boolean taken) {
        String n = name(raw);
        if (taken) throw Errors.conflict("NAME_TAKEN", "« " + n + " » existe déjà dans cet espace");
        return n;
    }

    private Map<UUID, Long> counts(String sql, Space s) {
        Map<UUID, Long> m = new HashMap<>();
        jdbc.sql(sql).param(s.getId()).query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getLong("n")))
                .list().forEach(e -> m.put(e.getKey(), e.getValue()));
        return m;
    }

    private Space space(AuthUser me, String key, Perm perm) {
        Space s = spaces.findByKey(key).orElseThrow(() -> Errors.notFound("Espace introuvable"));
        permissions.check(me, s, perm);
        return s;
    }
}
