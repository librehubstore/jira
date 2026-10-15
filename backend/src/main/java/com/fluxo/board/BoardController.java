package com.fluxo.board;

import org.springframework.http.HttpStatus;
import com.fluxo.space.Space;
import com.fluxo.auth.AuthUser;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.ticket.TicketViews.StatusRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/boards")
class BoardController {

    private final BoardService service;
    private final QuickFilterRepository quickFilters;
    private final PermissionService permissions;

    private final CfdService cfd;

    BoardController(BoardService service, QuickFilterRepository quickFilters, PermissionService permissions, CfdService cfd) {
        this.cfd = cfd;
        this.service = service;
        this.quickFilters = quickFilters;
        this.permissions = permissions;
    }

    record ColumnView(UUID id, String name, Integer wipMin, Integer wipMax, List<StatusRef> statuses) {}

    record FilterView(UUID id, String name, Map<String, Object> criteria) {}

    record BoardView(UUID id, String name, String spaceKey, String spaceName, boolean isDefault, List<String> spaceKeys,
                     Integer hideDoneDays,
                     Board.SwimlaneMode swimlaneMode, Board.WipMode wipMode, List<String> cardFields, boolean showSubtasks,
                     String baseFql, List<Board.LaneQuery> swimlaneQueries, List<Integer> workingDays, boolean backlogEnabled,
                     Board.ColorMode cardColorMode, List<Board.ColorQuery> cardColorQueries, boolean wipBlocking,
                     List<ColumnView> columns, List<StatusRef> unmappedStatuses, List<FilterView> quickFilters,
                     Set<Perm> myPermissions) {}

    record CreateBoard(@NotBlank String spaceKey, @NotBlank String name) {}

    record MoveRequest(@NotBlank String ticketKey, UUID statusId, String aboveKey, String belowKey, Integer version,
                       BoardService.LaneChange lane, UUID resolutionId, String comment) {}

    record SendRequest(@NotBlank String ticketKey, Boolean top) {}

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    BoardView get(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        return view(me, service.get(me, id));
    }

    @GetMapping("/{id}/cards")
    BoardService.BoardCards cards(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id,
                                  @RequestParam(defaultValue = "false") boolean backlog) {
        return service.cards(me, id, backlog);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    BoardView create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CreateBoard req) {
        return view(me, service.create(me, req.spaceKey(), req.name()));
    }

    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        service.delete(me, id);
    }

    /** Cumulative flow diagram (spec §4.15); {@code fql} narrows to a quick filter or swimlane query. */
    @GetMapping("/{id}/cfd")
    CfdService.Cfd cfd(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id,
                       @RequestParam java.time.LocalDate from, @RequestParam java.time.LocalDate to,
                       @RequestParam(required = false) String fql, @RequestParam(defaultValue = "Europe/Paris") String zone) {
        return cfd.compute(me, id, from, to, fql, java.time.ZoneId.of(zone));
    }

    record ReleaseRequest(UUID versionId, String name) {}

    record Released(int tickets) {}

    @PostMapping("/{id}/release")
    @Transactional
    Released release(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody ReleaseRequest req) {
        return new Released(service.release(me, id, req.versionId(), req.name()));
    }

    @PostMapping("/{id}/default")
    @Transactional
    BoardView makeDefault(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        return view(me, service.makeDefault(me, id));
    }

    /** Ids of the board tickets matching an FQL quick filter. */
    @GetMapping("/{id}/match")
    Set<UUID> match(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestParam String fql) {
        return service.match(me, id, fql);
    }

    @PostMapping("/{id}/move")
    @Transactional
    void move(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @Valid @RequestBody MoveRequest req) {
        service.move(me, id, req.ticketKey(), req.statusId(), req.aboveKey(), req.belowKey(), req.version(), req.lane(),
                req.resolutionId(), req.comment());
    }

    @PostMapping("/{id}/send")
    @Transactional
    void send(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @Valid @RequestBody SendRequest req) {
        service.sendTo(me, id, req.ticketKey(), !Boolean.FALSE.equals(req.top()));
    }

    @PatchMapping("/{id}")
    @Transactional
    BoardView update(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody BoardService.Settings req) {
        return view(me, service.update(me, id, req));
    }

    @PutMapping("/{id}/columns")
    @Transactional
    BoardView setColumns(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id,
                         @RequestBody List<BoardService.ColumnSpec> columns) {
        return view(me, service.setColumns(me, id, columns));
    }

    @PutMapping("/{id}/quick-filters")
    @Transactional
    List<FilterView> setQuickFilters(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id,
                                     @RequestBody List<BoardService.FilterSpec> filters) {
        return service.setQuickFilters(me, id, filters).stream()
                .map(f -> new FilterView(f.getId(), f.getName(), f.getCriteria())).toList();
    }

    private BoardView view(AuthUser me, Board b) {
        Set<UUID> mapped = BoardService.mappedStatusIds(b);
        return new BoardView(b.getId(), b.getName(), b.getSpace().getKey(), b.getSpace().getName(), b.isDefault(),
                service.visibleSpaces(me, b).stream().map(Space::getKey).toList(), b.getHideDoneDays(),
                b.getSwimlaneMode(), b.getWipMode(), b.getCardFields(), b.isShowSubtasks(), b.getBaseFql(), b.getSwimlaneQueries(),
                b.getWorkingDays(), b.isBacklogEnabled(), b.getCardColorMode(), b.getCardColorQueries(), b.isWipBlocking(),
                b.getColumns().stream().map(c -> new ColumnView(c.getId(), c.getName(), c.getWipMin(), c.getWipMax(),
                        c.getStatuses().stream().map(StatusRef::of).toList())).toList(),
                b.allSpaces().stream().flatMap(s -> s.allWorkflows().stream()).distinct().flatMap(w -> w.getStatuses().stream())
                        .filter(s -> !mapped.contains(s.getId())).map(StatusRef::of).toList(),
                quickFilters.findByBoardIdOrderByPositionAsc(b.getId()).stream()
                        .map(f -> new FilterView(f.getId(), f.getName(), f.getCriteria())).toList(),
                permissions.permissions(me, b.getSpace()));
    }
}
