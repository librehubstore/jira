package com.fluxo.workflow;

import com.fluxo.auth.AuthUser;
import com.fluxo.collab.TicketLinkRepository;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.space.Space;
import com.fluxo.space.SpaceRepository;
import com.fluxo.ticket.Ticket;
import com.fluxo.ticket.TicketType;
import com.fluxo.workflow.Transition.Rule;
import com.fluxo.workflow.Transition.Rules;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Workflow rules (spec §4.9): which transitions a user may take on a ticket, and the workflow editor. */
@Service
@Transactional
public class WorkflowService {

    public static final Set<String> CONDITIONS = Set.of("ASSIGNEE_ONLY", "REPORTER_ONLY", "PERMISSION", "ROLE", "GROUP", "SUBTASKS_DONE", "NO_OPEN_BLOCKERS");
    public static final Set<String> VALIDATORS = Set.of("FIELD_REQUIRED", "COMMENT_REQUIRED");
    public static final Set<String> ACTIONS = Set.of("ASSIGN_ME", "ASSIGN_REPORTER", "UNASSIGN", "CLEAR_FIELD", "SET_FIELD", "WEBHOOK");
    /** Ticket fields a validator may require or an action may clear / set (API field names). */
    public static final Set<String> FIELDS = Set.of("assigneeId", "priorityId", "dueDate", "startDate", "estimate", "labels", "description", "parent", "flagged");

    private final WorkflowRepository workflows;
    private final PermissionService permissions;
    private final SpaceRepository spaces;
    private final TicketLinkRepository links;
    private final JdbcClient jdbc;

    WorkflowService(WorkflowRepository workflows, PermissionService permissions, SpaceRepository spaces,
                    TicketLinkRepository links, JdbcClient jdbc) {
        this.workflows = workflows;
        this.permissions = permissions;
        this.spaces = spaces;
        this.links = links;
        this.jdbc = jdbc;
    }

    /** A way to reach {@code to}: a defined transition, or {@code null} in a simplified workflow. */
    public record Option(Transition transition, Status to) {
        public String name() { return transition == null ? to.getName() : transition.getName(); }
        public Rules rules() { return transition == null ? Rules.NONE : transition.getRules(); }
    }

    /** Transitions leaving the ticket's status, before conditions. */
    List<Option> outgoing(Ticket t) {
        Workflow wf = t.getSpace().workflowFor(t.getType());
        if (wf.isSimplified()) {
            return wf.getStatuses().stream().filter(s -> !s.equals(t.getStatus())).map(s -> new Option(null, s)).toList();
        }
        return wf.getTransitions().stream().filter(tr -> !tr.getTo().equals(t.getStatus()) && tr.startsFrom(t.getStatus()))
                .map(tr -> new Option(tr, tr.getTo())).toList();
    }

    /** What the « Changer le statut » menu lists: only transitions allowed for this user and ticket. */
    @Transactional(readOnly = true)
    public List<Option> available(AuthUser me, Ticket t) {
        if (!permissions.has(me, t.getSpace(), Perm.TRANSITION)) return List.of();
        return outgoing(t).stream().filter(o -> allowed(me, t, o.rules())).toList();
    }

    /** The transition used to reach {@code target}; 400 if none leads there, 403 if conditions refuse it. */
    public Option resolve(AuthUser me, Ticket t, Status target) {
        List<Option> ways = outgoing(t).stream().filter(o -> o.to().equals(target)).toList();
        if (ways.isEmpty()) {
            throw Errors.badRequest("NO_TRANSITION", "Aucune transition de « " + t.getStatus().getName() + " » vers « " + target.getName() + " »");
        }
        return ways.stream().filter(o -> allowed(me, t, o.rules())).findFirst()
                .orElseThrow(() -> Errors.forbidden("Transition « " + ways.getFirst().name() + " » non autorisée pour vous"));
    }

    boolean allowed(AuthUser me, Ticket t, Rules rules) {
        if (rules.conditions().isEmpty()) return true;
        var results = rules.conditions().stream().map(c -> holds(me, t, c));
        return rules.any() ? results.anyMatch(b -> b) : results.allMatch(b -> b);
    }

    private boolean holds(AuthUser me, Ticket t, Rule c) {
        return switch (c.type()) {
            case "ASSIGNEE_ONLY" -> t.getAssignee() != null && t.getAssignee().getId().equals(me.id());
            case "REPORTER_ONLY" -> t.getReporter().getId().equals(me.id());
            case "PERMISSION" -> permissions.has(me, t.getSpace(), Perm.valueOf(c.field()));
            case "ROLE" -> permissions.roleIds(me, t.getSpace()).contains(UUID.fromString(c.value()));
            case "GROUP" -> jdbc.sql("select count(*) from group_members where group_id = ? and user_id = ?")
                    .params(UUID.fromString(c.value()), me.id()).query(Long.class).optional().orElse(0L) > 0;
            case "SUBTASKS_DONE" -> jdbc.sql("""
                    select count(*) from tickets c join statuses s on s.id = c.status_id join ticket_types ty on ty.id = c.type_id
                    where c.parent_id = ? and ty.hierarchy_level = -1 and s.category <> 'DONE'
                    """).param(t.getId()).query(Long.class).optional().orElse(0L) == 0;
            case "NO_OPEN_BLOCKERS" -> links.blockedAmong(List.of(t.getId())).isEmpty();
            default -> false;
        };
    }

    /** Validators (spec §4.9): required fields and required comment. */
    public void validate(Ticket t, Option o, String comment) {
        for (Rule v : o.rules().validators()) {
            if (v.type().equals("COMMENT_REQUIRED") && (comment == null || comment.isBlank())) {
                throw Errors.badRequest("COMMENT_REQUIRED", "Un commentaire est obligatoire pour « " + o.name() + " »");
            }
            if (v.type().equals("FIELD_REQUIRED") && isEmpty(t, v.field())) {
                throw Errors.badRequest("FIELD_REQUIRED", "Le champ « " + v.field() + " » est obligatoire pour « " + o.name() + " »");
            }
        }
    }

    private static boolean isEmpty(Ticket t, String field) {
        return switch (field) {
            case "assigneeId" -> t.getAssignee() == null;
            case "dueDate" -> t.getDueDate() == null;
            case "startDate" -> t.getStartDate() == null;
            case "estimate" -> t.getEstimate() == null;
            case "labels" -> t.getLabels().isEmpty();
            case "description" -> t.getDescription() == null || t.getDescription().isBlank();
            case "parent" -> t.getParent() == null;
            default -> false;
        };
    }

    // --- Editor -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public Workflow get(AuthUser me, UUID id) {
        Workflow wf = workflows.findById(id).orElseThrow(() -> Errors.notFound("Workflow introuvable"));
        if (!me.admin() && usedBy(wf).stream().noneMatch(s -> permissions.has(me, s, Perm.BROWSE))) throw Errors.notFound("Workflow introuvable");
        return wf;
    }

    /** Only the global admin edits a shared workflow; a space admin edits the workflows of their space. */
    public boolean canEdit(AuthUser me, Workflow wf) {
        if (me.admin()) return true;
        return !wf.isShared() && usedBy(wf).stream().anyMatch(s -> permissions.has(me, s, Perm.ADMINISTER_SPACE));
    }

    private Workflow editable(AuthUser me, UUID id) {
        Workflow wf = get(me, id);
        if (!canEdit(me, wf)) throw Errors.forbidden(wf.isShared() ? "Seul l'administrateur modifie un workflow partagé" : "Action réservée aux administrateurs de l'espace");
        return wf;
    }

    @Transactional(readOnly = true)
    public List<Space> usedBy(Workflow wf) {
        List<UUID> ids = jdbc.sql("select id from spaces where workflow_id = :w union select space_id from space_type_workflows where workflow_id = :w")
                .param("w", wf.getId()).query(UUID.class).list();
        return spaces.findAllById(ids);
    }

    public record TransitionSpec(String name, List<UUID> fromStatusIds, UUID toStatusId, Rules rules) {}

    public Workflow setTransitions(AuthUser me, UUID id, List<TransitionSpec> specs) {
        Workflow wf = editable(me, id);
        Map<UUID, Status> byId = new HashMap<>();
        wf.getStatuses().forEach(s -> byId.put(s.getId(), s));
        wf.getTransitions().clear();
        workflows.flush();
        for (TransitionSpec spec : specs) {
            if (spec.name() == null || spec.name().isBlank()) throw Errors.badRequest("TRANSITION_NAME", "Chaque transition a un nom");
            Status to = byId.get(spec.toStatusId());
            if (to == null) throw Errors.badRequest("INVALID_STATUS", "Statut cible inconnu dans ce workflow");
            List<UUID> from = spec.fromStatusIds() == null ? List.of() : spec.fromStatusIds();
            if (!byId.keySet().containsAll(from)) throw Errors.badRequest("INVALID_STATUS", "Statut source inconnu dans ce workflow");
            Rules rules = spec.rules() == null ? Rules.NONE : spec.rules();
            check(rules.conditions(), CONDITIONS);
            check(rules.validators(), VALIDATORS);
            check(rules.actions(), ACTIONS);
            for (Rule r : rules.conditions()) {
                if (r.type().equals("PERMISSION")) {
                    try { Perm.valueOf(r.field()); } catch (RuntimeException e) { throw Errors.badRequest("INVALID_RULE", "Permission inconnue : " + r.field()); }
                }
                if (Set.of("ROLE", "GROUP").contains(r.type())) {
                    try { UUID.fromString(r.value()); } catch (RuntimeException e) { throw Errors.badRequest("INVALID_RULE", "Rôle ou groupe manquant"); }
                }
            }
            for (Rule r : rules.validators()) {
                if (r.type().equals("FIELD_REQUIRED") && !FIELDS.contains(r.field())) throw Errors.badRequest("INVALID_RULE", "Champ inconnu : " + r.field());
            }
            for (Rule r : rules.actions()) {
                if (Set.of("CLEAR_FIELD", "SET_FIELD").contains(r.type()) && !FIELDS.contains(r.field())) throw Errors.badRequest("INVALID_RULE", "Champ inconnu : " + r.field());
                if (r.type().equals("WEBHOOK") && (r.value() == null || !r.value().matches("https?://.+"))) throw Errors.badRequest("INVALID_RULE", "URL de webhook invalide");
            }
            wf.addTransition(spec.name().strip(), from, to, rules);
        }
        return wf;
    }

    private static void check(List<Rule> rules, Set<String> allowed) {
        for (Rule r : rules) {
            if (r == null || !allowed.contains(r.type())) throw Errors.badRequest("INVALID_RULE", "Règle inconnue : " + (r == null ? null : r.type()));
        }
    }

    public Status addStatus(AuthUser me, UUID id, String name, StatusCategory category) {
        Workflow wf = editable(me, id);
        if (name == null || name.isBlank() || category == null) throw Errors.badRequest("STATUS_NAME", "Nom et catégorie obligatoires");
        if (wf.getStatuses().stream().anyMatch(s -> s.getName().equalsIgnoreCase(name.strip()))) {
            throw Errors.conflict("STATUS_EXISTS", "Le statut « " + name.strip() + " » existe déjà");
        }
        Status s = wf.addStatus(name.strip(), category);
        workflows.flush();
        return s;
    }

    public Status updateStatus(AuthUser me, UUID id, UUID statusId, String name, StatusCategory category) {
        Workflow wf = editable(me, id);
        Status s = status(wf, statusId);
        if (name != null && !name.isBlank()) {
            if (wf.getStatuses().stream().anyMatch(o -> o != s && o.getName().equalsIgnoreCase(name.strip()))) {
                throw Errors.conflict("STATUS_EXISTS", "Le statut « " + name.strip() + " » existe déjà");
            }
            s.setName(name.strip());
        }
        if (category != null) s.setCategory(category);
        return s;
    }

    /** A status used by tickets is only deleted when they move to another status (spec §4.9). */
    public void deleteStatus(AuthUser me, UUID id, UUID statusId, UUID migrateTo) {
        Workflow wf = editable(me, id);
        Status s = status(wf, statusId);
        if (wf.getStatuses().size() == 1) throw Errors.badRequest("LAST_STATUS", "Un workflow garde au moins un statut");
        long used = jdbc.sql("select count(*) from tickets where status_id = ?").param(statusId).query(Long.class).optional().orElse(0L);
        if (used > 0) {
            if (migrateTo == null) throw Errors.conflict("STATUS_IN_USE", used + " ticket(s) utilisent ce statut : choisissez un statut de remplacement");
            Status target = status(wf, migrateTo);
            if (target == s) throw Errors.badRequest("INVALID_STATUS", "Choisissez un autre statut");
            // ponytail: bulk move without history entries; add per-ticket history if audits need it.
            jdbc.sql("update tickets set status_id = ?, version = version + 1, updated_at = now() where status_id = ?").params(target.getId(), statusId).update();
        }
        wf.getTransitions().removeIf(tr -> tr.getTo() == s);
        wf.getTransitions().forEach(tr -> tr.getFromStatusIds().remove(statusId));
        wf.getStatuses().remove(s);
        for (int i = 0; i < wf.getStatuses().size(); i++) wf.getStatuses().get(i).setPosition(i);
    }

    private static Status status(Workflow wf, UUID statusId) {
        return wf.getStatuses().stream().filter(x -> x.getId().equals(statusId)).findFirst()
                .orElseThrow(() -> Errors.notFound("Statut introuvable"));
    }

    // --- Workflow per ticket type ------------------------------------------------

    /**
     * Gives the type its own workflow, copied from the space's (statuses and transitions); tickets of the type and
     * board columns follow the copied statuses by name.
     */
    public Workflow ownWorkflowForType(AuthUser me, Space space, TicketType type) {
        permissions.check(me, space, Perm.ADMINISTER_SPACE);
        if (!space.getTicketTypes().contains(type)) throw Errors.badRequest("TYPE_REQUIRED", "Type non disponible dans l'espace");
        if (space.getTypeWorkflows().containsKey(type)) throw Errors.conflict("TYPE_WORKFLOW_EXISTS", "Ce type a déjà son workflow");
        Workflow base = space.getWorkflow();
        Workflow copy = new Workflow(space.getKey() + " · " + type.getName());
        Map<UUID, Status> map = new HashMap<>();
        base.getStatuses().forEach(s -> map.put(s.getId(), copy.addStatus(s.getName(), s.getCategory())));
        workflows.saveAndFlush(copy); // statuses get their ids before transitions refer to them
        base.getTransitions().forEach(tr -> copy.addTransition(tr.getName(),
                tr.getFromStatusIds().stream().map(id -> map.get(id).getId()).toList(), map.get(tr.getTo().getId()), tr.getRules()));
        workflows.flush();
        space.getTypeWorkflows().put(type, copy);
        remap(space, type, base, copy);
        jdbc.sql("""
                insert into board_column_statuses (column_id, status_id)
                select bcs.column_id, n.id from board_column_statuses bcs
                join board_columns c on c.id = bcs.column_id join boards b on b.id = c.board_id
                join statuses o on o.id = bcs.status_id and o.workflow_id = :old
                join statuses n on n.workflow_id = :new and lower(n.name) = lower(o.name)
                where b.space_id = :space or exists (select 1 from board_spaces bs where bs.board_id = b.id and bs.space_id = :space)
                on conflict do nothing
                """).param("old", base.getId()).param("new", copy.getId()).param("space", space.getId()).update();
        return copy;
    }

    /** Back to the space workflow; the type's own workflow is deleted. */
    public void dropWorkflowForType(AuthUser me, Space space, TicketType type) {
        permissions.check(me, space, Perm.ADMINISTER_SPACE);
        Workflow own = space.getTypeWorkflows().remove(type);
        if (own == null) return;
        spaces.flush();
        remap(space, type, own, space.getWorkflow());
        if (usedBy(own).isEmpty() && !own.isShared()) workflows.delete(own);
    }

    /** Tickets of the type move to the same-named status of the new workflow, else to its initial status. */
    private void remap(Space space, TicketType type, Workflow from, Workflow to) {
        jdbc.sql("""
                update tickets t set status_id = coalesce(
                    (select n.id from statuses o join statuses n on lower(n.name) = lower(o.name) and n.workflow_id = :to where o.id = t.status_id),
                    :initial), version = version + 1
                where t.space_id = :space and t.type_id = :type
                """).param("to", to.getId()).param("initial", to.initialStatus().getId())
                .param("space", space.getId()).param("type", type.getId()).update();
    }
}
