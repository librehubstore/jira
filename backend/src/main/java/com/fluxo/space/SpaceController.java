package com.fluxo.space;

import com.fluxo.auth.AuthUser;
import com.fluxo.board.BoardRepository;
import com.fluxo.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/spaces")
class SpaceController {

    private final SpaceService service;
    private final SpaceRepository spaces;
    private final SpaceRoleRepository roles;
    private final SpaceMembershipRepository memberships;
    private final BoardRepository boards;
    private final PermissionService permissions;

    SpaceController(SpaceService service, SpaceRepository spaces, SpaceRoleRepository roles,
                    SpaceMembershipRepository memberships, BoardRepository boards, PermissionService permissions) {
        this.service = service;
        this.spaces = spaces;
        this.roles = roles;
        this.memberships = memberships;
        this.boards = boards;
        this.permissions = permissions;
    }

    record UserRef(UUID id, String username, String displayName, boolean active, String avatar) {
        static UserRef of(User u) {
            return u == null ? null : new UserRef(u.getId(), u.getUsername(), u.getDisplayName(), u.isActive(), u.avatarUrl());
        }
    }

    record SpaceSummary(UUID id, String key, String name, String description, boolean archived, UserRef lead,
                        Set<Perm> myPermissions) {}

    record StatusDto(UUID id, String name, String category) {}

    record TypeDto(UUID id, String name, String icon, String color, int hierarchyLevel) {}

    record BoardRef(UUID id, String name, boolean isDefault) {}

    record SpaceDetail(UUID id, String key, String name, String description, boolean archived, UserRef lead,
                       Set<Perm> myPermissions, List<StatusDto> statuses, List<TypeDto> ticketTypes,
                       List<BoardRef> boards, String commentVisibilityRoleId, String estimateUnit) {}

    record CreateSpace(@NotBlank String key, @NotBlank @Size(max = 128) String name, String description, UUID leadId) {}

    record UpdateSpace(@Size(max = 128) String name, String description, UUID leadId, String key, Boolean archived,
                       String commentVisibilityRoleId, String estimateUnit) {}

    record RoleDto(UUID id, String name, Set<Perm> permissions) {
        static RoleDto of(SpaceRole r) {
            return new RoleDto(r.getId(), r.getName(), r.getPermissions());
        }
    }

    record MemberDto(UUID id, UserRef user, UUID groupId, String groupName, UUID roleId, String roleName) {
        static MemberDto of(SpaceMembership m) {
            return new MemberDto(m.getId(), UserRef.of(m.getUser()),
                    m.getGroup() == null ? null : m.getGroup().getId(),
                    m.getGroup() == null ? null : m.getGroup().getName(),
                    m.getRole().getId(), m.getRole().getName());
        }
    }

    record AddMember(UUID userId, UUID groupId, @NotNull UUID roleId) {}

    record ChangeRole(@NotNull UUID roleId) {}

    @GetMapping
    @Transactional(readOnly = true)
    List<SpaceSummary> list(@AuthenticationPrincipal AuthUser me, @RequestParam(defaultValue = "false") boolean includeArchived) {
        Map<UUID, Set<Perm>> visible = permissions.visibleSpaces(me);
        return spaces.findByIdInOrderByNameAsc(visible.keySet()).stream()
                .filter(s -> includeArchived || !s.isArchived())
                .map(s -> {
                    Set<Perm> p = EnumSet.copyOf(visible.get(s.getId()));
                    if (s.isArchived()) p.retainAll(Perm.ARCHIVED);
                    return new SpaceSummary(s.getId(), s.getKey(), s.getName(), s.getDescription(), s.isArchived(),
                            UserRef.of(s.getLead()), p);
                })
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    SpaceDetail create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CreateSpace req) {
        Space s = service.create(me, req.key(), req.name(), req.description(), req.leadId());
        return detail(me, s);
    }

    @GetMapping("/{key}")
    @Transactional(readOnly = true)
    SpaceDetail get(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        return detail(me, service.get(me, key));
    }

    @PatchMapping("/{key}")
    @Transactional
    SpaceDetail update(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @Valid @RequestBody UpdateSpace req) {
        Space s = service.update(me, key, req.name(), req.description(), req.leadId(), req.key(), req.archived());
        if (req.commentVisibilityRoleId() != null) service.setCommentVisibility(me, s, req.commentVisibilityRoleId());
        if (req.estimateUnit() != null) service.setEstimateUnit(me, s, req.estimateUnit());
        return detail(me, s);
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestParam String confirm) {
        service.delete(me, key, confirm);
    }

    @GetMapping("/{key}/roles")
    @Transactional(readOnly = true)
    List<RoleDto> roles(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        return roles.findBySpaceIdOrderByNameAsc(service.get(me, key).getId()).stream().map(RoleDto::of).toList();
    }

    @PutMapping("/{key}/roles/{roleId}")
    @Transactional
    RoleDto updateRole(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @PathVariable UUID roleId,
                       @RequestBody Set<Perm> perms) {
        return RoleDto.of(service.updateRole(me, key, roleId, EnumSet.copyOf(perms.isEmpty() ? EnumSet.noneOf(Perm.class) : perms)));
    }

    @GetMapping("/{key}/members")
    @Transactional(readOnly = true)
    List<MemberDto> members(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        return memberships.findBySpaceId(service.get(me, key).getId()).stream()
                .map(MemberDto::of)
                .sorted(Comparator.comparing(m -> m.user() != null ? m.user().displayName() : m.groupName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @PostMapping("/{key}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    MemberDto addMember(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @Valid @RequestBody AddMember req) {
        return MemberDto.of(service.addMember(me, key, req.userId(), req.groupId(), req.roleId()));
    }

    @PatchMapping("/{key}/members/{id}")
    @Transactional
    MemberDto changeRole(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @PathVariable UUID id,
                         @Valid @RequestBody ChangeRole req) {
        return MemberDto.of(service.changeMemberRole(me, key, id, req.roleId()));
    }

    @DeleteMapping("/{key}/members/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void removeMember(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @PathVariable UUID id) {
        service.removeMember(me, key, id);
    }

    private SpaceDetail detail(AuthUser me, Space s) {
        return new SpaceDetail(s.getId(), s.getKey(), s.getName(), s.getDescription(), s.isArchived(),
                UserRef.of(s.getLead()), permissions.permissions(me, s),
                s.allWorkflows().stream().flatMap(w -> w.getStatuses().stream())
                        .map(st -> new StatusDto(st.getId(), st.getName(), st.getCategory().name())).toList(),
                s.getTicketTypes().stream()
                        .sorted(Comparator.comparingInt((com.fluxo.ticket.TicketType t) -> -t.getHierarchyLevel()).thenComparing(t -> t.getName()))
                        .map(t -> new TypeDto(t.getId(), t.getName(), t.getIcon(), t.getColor(), t.getHierarchyLevel())).toList(),
                boards.findCovering(s.getId()).stream()
                        .map(b -> new BoardRef(b.getId(), b.getName(), b.isDefault())).toList(),
                (String) s.getSettings().get("commentVisibilityRoleId"), SpaceService.estimateUnit(s));
    }
}
