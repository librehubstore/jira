package com.fluxo.ticket;

import com.fluxo.auth.AuthUser;
import com.fluxo.space.PermissionService;
import com.fluxo.space.Space;
import com.fluxo.space.SpaceService;
import com.fluxo.ticket.TicketViews.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
class TicketController {

    private final TicketService service;
    private final TicketRepository tickets;
    private final HistoryRepository history;
    private final LabelRepository labels;
    private final SpaceService spaces;
    private final PermissionService permissions;
    private final TicketTypeRepository types;
    private final PriorityRepository priorities;
    private final ResolutionRepository resolutions;

    TicketController(TicketService service, TicketRepository tickets, HistoryRepository history, LabelRepository labels,
                     SpaceService spaces, PermissionService permissions, TicketTypeRepository types,
                     PriorityRepository priorities, ResolutionRepository resolutions) {
        this.service = service;
        this.tickets = tickets;
        this.history = history;
        this.labels = labels;
        this.spaces = spaces;
        this.permissions = permissions;
        this.types = types;
        this.priorities = priorities;
        this.resolutions = resolutions;
    }

    record Meta(List<TypeRef> types, List<PriorityRef> priorities, List<NamedRef> resolutions) {}

    record TransitionRequest(@NotNull UUID statusId, UUID resolutionId, Integer version, String comment) {}

    record MoveRequest(@NotBlank String spaceKey, UUID typeId) {}

    /** Global reference data (types, priorities, resolutions). */
    @GetMapping("/meta")
    @Transactional(readOnly = true)
    Meta meta() {
        return new Meta(types.findAllByOrderByHierarchyLevelDescNameAsc().stream().map(TypeRef::of).toList(),
                priorities.findAllByOrderByRankAsc().stream().map(PriorityRef::of).toList(),
                resolutions.findAllByOrderByNameAsc().stream().map(r -> new NamedRef(r.getId(), r.getName())).toList());
    }

    @PostMapping("/spaces/{spaceKey}/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    TicketDetail create(@AuthenticationPrincipal AuthUser me, @PathVariable String spaceKey, @RequestBody JsonNode body) {
        return detail(me, service.create(me, spaces.get(me, spaceKey), body));
    }

    @GetMapping("/tickets/{key}")
    @Transactional(readOnly = true)
    TicketDetail get(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        return detail(me, service.get(me, key));
    }

    @PatchMapping("/tickets/{key}")
    @Transactional
    TicketDetail update(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestBody JsonNode body) {
        Ticket t = service.update(me, key, body);
        tickets.flush(); // bump @Version before returning it
        return detail(me, t);
    }

    @PostMapping("/tickets/{key}/transition")
    @Transactional
    TicketDetail transition(@AuthenticationPrincipal AuthUser me, @PathVariable String key,
                            @Valid @RequestBody TransitionRequest req) {
        Ticket t = service.transition(me, key, req.statusId(), req.resolutionId(), req.version(), req.comment());
        tickets.flush();
        return detail(me, t);
    }

    @GetMapping("/tickets/{key}/move-preview")
    TicketService.MovePreview movePreview(@AuthenticationPrincipal AuthUser me, @PathVariable String key,
                                          @RequestParam String spaceKey) {
        return service.previewMove(me, key, spaceKey);
    }

    @PostMapping("/tickets/{key}/move")
    @Transactional
    TicketDetail move(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @Valid @RequestBody MoveRequest req) {
        Ticket t = service.move(me, key, req.spaceKey(), req.typeId());
        tickets.flush();
        return detail(me, t);
    }

    @DeleteMapping("/tickets/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthUser me, @PathVariable String key,
                @RequestParam(defaultValue = "false") boolean withSubtasks) {
        service.delete(me, key, withSubtasks);
    }

    @GetMapping("/tickets/{key}/history")
    @Transactional(readOnly = true)
    List<HistoryView> history(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        return history.findByTicket(service.get(me, key).getId()).stream().map(HistoryView::of).toList();
    }

    @GetMapping("/spaces/{spaceKey}/assignable")
    @Transactional(readOnly = true)
    List<UserRef> assignable(@AuthenticationPrincipal AuthUser me, @PathVariable String spaceKey) {
        return permissions.assignableUsers(spaces.get(me, spaceKey)).stream().map(UserRef::of).toList();
    }

    @GetMapping("/spaces/{spaceKey}/labels")
    @Transactional(readOnly = true)
    List<String> labels(@AuthenticationPrincipal AuthUser me, @PathVariable String spaceKey,
                        @RequestParam(defaultValue = "") String q) {
        return labels.search(spaces.get(me, spaceKey).getId(), q).stream().map(Label::getName).limit(20).toList();
    }

    @GetMapping("/spaces/{spaceKey}/field-defaults")
    @Transactional(readOnly = true)
    List<TicketService.DefaultValue> defaults(@AuthenticationPrincipal AuthUser me, @PathVariable String spaceKey) {
        return service.getDefaults(spaces.get(me, spaceKey));
    }

    @PutMapping("/spaces/{spaceKey}/field-defaults")
    @Transactional
    List<TicketService.DefaultValue> setDefaults(@AuthenticationPrincipal AuthUser me, @PathVariable String spaceKey,
                                                 @RequestBody List<TicketService.DefaultValue> values) {
        Space space = spaces.get(me, spaceKey);
        service.setDefaults(me, space, values);
        return service.getDefaults(space);
    }

    private TicketDetail detail(AuthUser me, Ticket t) {
        return TicketDetail.of(t, tickets.findChildren(t.getId()), permissions.permissions(me, t.getSpace()));
    }
}
