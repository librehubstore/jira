package com.fluxo.search;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.space.SpaceRepository;
import com.fluxo.ticket.TicketViews.UserRef;
import com.fluxo.user.GroupRepository;
import com.fluxo.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Saved filters: create, share, favourite (spec §4.11). Only the owner (or an admin) edits or deletes. */
@RestController
@RequestMapping("/api/filters")
class SavedFilterController {

    private final SavedFilterRepository filters;
    private final SearchService search;
    private final PermissionService permissions;
    private final UserRepository users;
    private final GroupRepository groups;
    private final SpaceRepository spaces;
    private final JdbcClient jdbc;

    SavedFilterController(SavedFilterRepository filters, SearchService search, PermissionService permissions,
                          UserRepository users, GroupRepository groups, SpaceRepository spaces, JdbcClient jdbc) {
        this.filters = filters;
        this.search = search;
        this.permissions = permissions;
        this.users = users;
        this.groups = groups;
        this.spaces = spaces;
        this.jdbc = jdbc;
    }

    record FilterRequest(@NotBlank @Size(max = 128) String name, @NotBlank String fql, List<String> columns,
                         SavedFilter.Scope shareScope, UUID shareGroupId, String shareSpaceKey) {}

    record FilterView(UUID id, String name, String fql, List<String> columns, SavedFilter.Scope shareScope,
                      UUID shareGroupId, String shareSpaceKey, UserRef owner, boolean mine, boolean favorite) {}

    @GetMapping
    @Transactional(readOnly = true)
    List<FilterView> list(@AuthenticationPrincipal AuthUser me) {
        Set<UUID> favorites = favorites(me);
        return filters.findVisible(me.id(), permissions.visibleSpaces(me).keySet()).stream()
                .map(f -> view(f, me, favorites)).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    FilterView get(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        return view(visible(me, id), me, favorites(me));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    FilterView create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody FilterRequest req) {
        SavedFilter f = new SavedFilter(users.getReferenceById(me.id()), req.name().strip(), req.fql());
        apply(me, f, req);
        return view(filters.save(f), me, favorites(me));
    }

    @PatchMapping("/{id}")
    @Transactional
    FilterView update(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @Valid @RequestBody FilterRequest req) {
        SavedFilter f = owned(me, id);
        f.setName(req.name().strip());
        f.setFql(req.fql());
        apply(me, f, req);
        return view(f, me, favorites(me));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        filters.delete(owned(me, id));
    }

    @PutMapping("/{id}/favorite")
    @Transactional
    void favorite(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        visible(me, id);
        jdbc.sql("insert into saved_filter_favorites (filter_id, user_id) values (?, ?) on conflict do nothing").params(id, me.id()).update();
    }

    @DeleteMapping("/{id}/favorite")
    @Transactional
    void unfavorite(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        jdbc.sql("delete from saved_filter_favorites where filter_id = ? and user_id = ?").params(id, me.id()).update();
    }

    private void apply(AuthUser me, SavedFilter f, FilterRequest req) {
        search.parse(req.fql()); // reject invalid FQL with its position
        f.setColumns(req.columns() == null ? List.of() : req.columns());
        SavedFilter.Scope scope = req.shareScope() == null ? SavedFilter.Scope.PRIVATE : req.shareScope();
        f.setShareScope(scope);
        f.setShareGroup(null);
        f.setShareSpace(null);
        switch (scope) {
            case GROUP -> f.setShareGroup(groups.findById(req.shareGroupId() == null ? new UUID(0, 0) : req.shareGroupId())
                    .orElseThrow(() -> Errors.badRequest("INVALID_SHARE", "Groupe de partage introuvable")));
            case SPACE -> {
                var space = spaces.findByKey(req.shareSpaceKey() == null ? "" : req.shareSpaceKey())
                        .filter(s -> permissions.has(me, s, Perm.BROWSE))
                        .orElseThrow(() -> Errors.badRequest("INVALID_SHARE", "Espace de partage introuvable"));
                f.setShareSpace(space);
            }
            default -> { }
        }
    }

    private SavedFilter visible(AuthUser me, UUID id) {
        return filters.findVisible(me.id(), permissions.visibleSpaces(me).keySet()).stream().filter(f -> f.getId().equals(id))
                .findFirst().orElseThrow(() -> Errors.notFound("Filtre introuvable"));
    }

    private SavedFilter owned(AuthUser me, UUID id) {
        SavedFilter f = visible(me, id);
        if (!f.getOwner().getId().equals(me.id()) && !me.admin()) throw Errors.forbidden("Seul le propriétaire modifie ce filtre");
        return f;
    }

    private Set<UUID> favorites(AuthUser me) {
        return new HashSet<>(jdbc.sql("select filter_id from saved_filter_favorites where user_id = ?").param(me.id()).query(UUID.class).list());
    }

    private static FilterView view(SavedFilter f, AuthUser me, Set<UUID> favorites) {
        return new FilterView(f.getId(), f.getName(), f.getFql(), f.getColumns(), f.getShareScope(),
                f.getShareGroup() == null ? null : f.getShareGroup().getId(),
                f.getShareSpace() == null ? null : f.getShareSpace().getKey(),
                UserRef.of(f.getOwner()), f.getOwner().getId().equals(me.id()), favorites.contains(f.getId()));
    }
}
