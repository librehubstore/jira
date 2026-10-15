package com.fluxo.workflow;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.space.Space;
import com.fluxo.space.SpaceRepository;
import com.fluxo.ticket.Ticket;
import com.fluxo.ticket.TicketService;
import com.fluxo.ticket.TicketType;
import com.fluxo.ticket.TicketTypeRepository;
import com.fluxo.ticket.TicketViews.StatusRef;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Workflow editor (spec §4.9) and the transitions offered on a ticket. */
@RestController
@RequestMapping("/api")
class WorkflowController {

    private final WorkflowService service;
    private final TicketService tickets;
    private final SpaceRepository spaces;
    private final TicketTypeRepository types;
    private final PermissionService permissions;
    private final JdbcClient jdbc;

    WorkflowController(WorkflowService service, TicketService tickets, SpaceRepository spaces, TicketTypeRepository types,
                       PermissionService permissions, JdbcClient jdbc) {
        this.service = service;
        this.tickets = tickets;
        this.spaces = spaces;
        this.types = types;
        this.permissions = permissions;
        this.jdbc = jdbc;
    }

    record AvailableTransition(UUID id, String name, StatusRef to, Transition.Screen screen, boolean commentRequired) {}

    record StatusView(UUID id, String name, String category, long ticketCount) {}

    record TransitionView(UUID id, String name, List<UUID> fromStatusIds, UUID toStatusId, Transition.Rules rules) {}

    record WorkflowView(UUID id, String name, boolean shared, boolean editable, List<StatusView> statuses,
                        List<TransitionView> transitions, List<String> usedBy) {}

    record TypeWorkflow(UUID typeId, String typeName, UUID workflowId) {}

    record SpaceWorkflows(UUID defaultWorkflowId, List<TypeWorkflow> types, List<WorkflowView> workflows) {}

    record StatusRequest(String name, StatusCategory category) {}

    /** Transitions the user may take now (« Changer le statut », board drop targets). */
    @GetMapping("/tickets/{key}/transitions")
    @Transactional(readOnly = true)
    List<AvailableTransition> transitions(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        Ticket t = tickets.get(me, key);
        return service.available(me, t).stream().map(o -> new AvailableTransition(
                o.transition() == null ? null : o.transition().getId(), o.name(), StatusRef.of(o.to()), o.rules().screen(),
                o.rules().validators().stream().anyMatch(v -> v.type().equals("COMMENT_REQUIRED")))).toList();
    }

    @GetMapping("/spaces/{key}/workflows")
    @Transactional(readOnly = true)
    SpaceWorkflows ofSpace(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        Space s = space(me, key);
        return new SpaceWorkflows(s.getWorkflow().getId(),
                s.getTicketTypes().stream().sorted(Comparator.comparing(TicketType::getName))
                        .map(ty -> new TypeWorkflow(ty.getId(), ty.getName(), s.workflowFor(ty).getId())).toList(),
                s.allWorkflows().stream().map(w -> view(me, w)).toList());
    }

    @GetMapping("/workflows/{id}")
    @Transactional(readOnly = true)
    WorkflowView get(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        return view(me, service.get(me, id));
    }

    @PutMapping("/workflows/{id}/transitions")
    @Transactional
    WorkflowView setTransitions(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id,
                                @RequestBody List<WorkflowService.TransitionSpec> specs) {
        return view(me, service.setTransitions(me, id, specs));
    }

    @PostMapping("/workflows/{id}/statuses")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    WorkflowView addStatus(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody StatusRequest req) {
        service.addStatus(me, id, req.name(), req.category());
        return view(me, service.get(me, id));
    }

    @PatchMapping("/workflows/{id}/statuses/{statusId}")
    @Transactional
    WorkflowView updateStatus(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @PathVariable UUID statusId,
                              @RequestBody StatusRequest req) {
        service.updateStatus(me, id, statusId, req.name(), req.category());
        return view(me, service.get(me, id));
    }

    @DeleteMapping("/workflows/{id}/statuses/{statusId}")
    @Transactional
    WorkflowView deleteStatus(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @PathVariable UUID statusId,
                              @RequestParam(required = false) UUID migrateTo) {
        service.deleteStatus(me, id, statusId, migrateTo);
        return view(me, service.get(me, id));
    }

    @PostMapping("/spaces/{key}/workflows/types/{typeId}")
    @Transactional
    SpaceWorkflows ownForType(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @PathVariable UUID typeId) {
        Space s = space(me, key);
        service.ownWorkflowForType(me, s, type(typeId));
        return ofSpace(me, key);
    }

    @DeleteMapping("/spaces/{key}/workflows/types/{typeId}")
    @Transactional
    SpaceWorkflows dropForType(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @PathVariable UUID typeId) {
        Space s = space(me, key);
        service.dropWorkflowForType(me, s, type(typeId));
        return ofSpace(me, key);
    }

    private Space space(AuthUser me, String key) {
        Space s = spaces.findByKey(key).orElseThrow(() -> Errors.notFound("Espace introuvable"));
        permissions.check(me, s, Perm.BROWSE);
        return s;
    }

    private TicketType type(UUID id) {
        return types.findById(id).orElseThrow(() -> Errors.notFound("Type introuvable"));
    }

    private WorkflowView view(AuthUser me, Workflow w) {
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.sql("select t.status_id as id, count(*) as n from tickets t join statuses s on s.id = t.status_id where s.workflow_id = ? group by t.status_id")
                .param(w.getId()).query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getLong("n")))
                .list().forEach(e -> counts.put(e.getKey(), e.getValue()));
        return new WorkflowView(w.getId(), w.getName(), w.isShared(), service.canEdit(me, w),
                w.getStatuses().stream().map(s -> new StatusView(s.getId(), s.getName(), s.getCategory().name(), counts.getOrDefault(s.getId(), 0L))).toList(),
                w.getTransitions().stream().map(t -> new TransitionView(t.getId(), t.getName(), t.getFromStatusIds(), t.getTo().getId(), t.getRules())).toList(),
                service.usedBy(w).stream().map(Space::getKey).sorted().toList());
    }
}
