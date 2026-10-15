package com.fluxo.ticket;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.space.Space;
import com.fluxo.space.SpaceRepository;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** Ticket templates (spec §4.3): saved by a space admin, offered at creation; they can create subtasks. */
@RestController
@RequestMapping("/api")
class TemplateController {

    @Entity(name = "TicketTemplate")
    @Table(name = "ticket_templates")
    static class Template {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        UUID id;
        @Column(nullable = false)
        UUID spaceId;
        @Column(nullable = false)
        String name;
        UUID typeId;
        @JdbcTypeCode(SqlTypes.JSON)
        @Column(nullable = false)
        Map<String, Object> fieldValues = new HashMap<>();
        @JdbcTypeCode(SqlTypes.JSON)
        @Column(nullable = false)
        List<String> subtasks = new ArrayList<>();
    }

    record TemplateView(UUID id, String name, UUID typeId, Map<String, Object> values, List<String> subtasks) {}

    record TemplateRequest(String name, UUID typeId, Map<String, Object> values, List<String> subtasks) {}

    private final TemplateRepository templates;
    private final SpaceRepository spaces;
    private final PermissionService permissions;
    private final TicketService tickets;
    private final TicketRepository repo;
    private final JsonMapper json;

    TemplateController(TemplateRepository templates, SpaceRepository spaces, PermissionService permissions, TicketService tickets,
                       TicketRepository repo, JsonMapper json) {
        this.templates = templates;
        this.spaces = spaces;
        this.permissions = permissions;
        this.tickets = tickets;
        this.repo = repo;
        this.json = json;
    }

    @GetMapping("/spaces/{key}/templates")
    @Transactional(readOnly = true)
    List<TemplateView> list(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        Space s = space(me, key, Perm.BROWSE);
        return templates.findBySpaceIdOrderByNameAsc(s.getId()).stream().map(TemplateController::view).toList();
    }

    @PostMapping("/spaces/{key}/templates")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    TemplateView create(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestBody TemplateRequest req) {
        Space s = space(me, key, Perm.ADMINISTER_SPACE);
        Template t = new Template();
        t.spaceId = s.getId();
        return view(templates.saveAndFlush(fill(s, t, req)));
    }

    @PutMapping("/templates/{id}")
    @Transactional
    TemplateView update(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody TemplateRequest req) {
        Template t = template(id);
        return view(fill(admin(me, t), t, req));
    }

    @DeleteMapping("/templates/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        Template t = template(id);
        admin(me, t);
        templates.delete(t);
    }

    /** Creates a ticket from the template; {@code body} overrides template values (summary at least), subtasks follow. */
    @PostMapping("/templates/{id}/tickets")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    TicketViews.TicketDetail createTicket(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody ObjectNode body) {
        Template tpl = template(id);
        Space s = spaces.findById(tpl.spaceId).orElseThrow();
        ObjectNode values = json.valueToTree(tpl.fieldValues);
        if (tpl.typeId != null) values.put("typeId", tpl.typeId.toString());
        values.setAll(body);
        Ticket t = tickets.create(me, s, values);
        if (!tpl.subtasks.isEmpty()) {
            TicketType subtask = s.getTicketTypes().stream().filter(ty -> ty.getHierarchyLevel() == -1).findFirst()
                    .orElseThrow(() -> Errors.badRequest("NO_SUBTASK_TYPE", "L'espace n'a pas de type sous-tâche"));
            for (String summary : tpl.subtasks) {
                ObjectNode sub = json.createObjectNode().put("summary", summary).put("typeId", subtask.getId().toString()).put("parent", t.getKey());
                tickets.create(me, s, sub);
            }
        }
        repo.flush();
        return TicketViews.TicketDetail.of(t, repo.findChildren(t.getId()), permissions.permissions(me, s));
    }

    private Template fill(Space s, Template t, TemplateRequest req) {
        if (req.name() == null || req.name().isBlank()) throw Errors.badRequest("TEMPLATE_NAME", "Le modèle a un nom");
        if (req.typeId() != null && s.getTicketTypes().stream().noneMatch(ty -> ty.getId().equals(req.typeId()))) {
            throw Errors.badRequest("TYPE_REQUIRED", "Type non disponible dans l'espace");
        }
        Map<String, Object> values = req.values() == null ? Map.of() : req.values();
        Set<String> allowed = Set.of("summary", "description", "priorityId", "assigneeId", "dueDate", "startDate", "estimate", "labels", "flagged");
        if (!allowed.containsAll(values.keySet())) throw Errors.badRequest("UNKNOWN_FIELD", "Champ non disponible dans un modèle");
        t.name = req.name().strip();
        t.typeId = req.typeId();
        t.fieldValues = new HashMap<>(values);
        t.subtasks = req.subtasks() == null ? new ArrayList<>() : req.subtasks().stream().filter(x -> x != null && !x.isBlank()).map(String::strip).toList();
        return t;
    }

    private Template template(UUID id) {
        return templates.findById(id).orElseThrow(() -> Errors.notFound("Modèle introuvable"));
    }

    private Space admin(AuthUser me, Template t) {
        Space s = spaces.findById(t.spaceId).orElseThrow();
        permissions.check(me, s, Perm.ADMINISTER_SPACE);
        return s;
    }

    private Space space(AuthUser me, String key, Perm perm) {
        Space s = spaces.findByKey(key).orElseThrow(() -> Errors.notFound("Espace introuvable"));
        permissions.check(me, s, perm);
        return s;
    }

    private static TemplateView view(Template t) {
        return new TemplateView(t.id, t.name, t.typeId, t.fieldValues, t.subtasks);
    }
}
