package com.fluxo.space;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Single entry point for space access control (spec §2). Every service that reads or writes
 * space data must go through here.
 */
@Service
@Transactional(readOnly = true)
public class PermissionService {

    private final SpaceMembershipRepository memberships;
    private final SpaceRepository spaces;

    PermissionService(SpaceMembershipRepository memberships, SpaceRepository spaces) {
        this.memberships = memberships;
        this.spaces = spaces;
    }

    public Set<Perm> permissions(AuthUser user, Space space) {
        Set<Perm> perms = EnumSet.noneOf(Perm.class);
        if (user.admin()) {
            perms.addAll(Perm.ALL);
        } else {
            memberships.findForUserInSpace(space.getId(), user.id())
                    .forEach(m -> perms.addAll(m.getRole().getPermissions()));
        }
        if (space.isArchived()) perms.retainAll(Perm.ARCHIVED);
        return perms;
    }

    /** Roles the user holds in the space, directly or through a group (workflow condition « membre du rôle »). */
    public Set<UUID> roleIds(AuthUser user, Space space) {
        Set<UUID> ids = new HashSet<>();
        memberships.findForUserInSpace(space.getId(), user.id()).forEach(m -> ids.add(m.getRole().getId()));
        return ids;
    }

    public boolean has(AuthUser user, Space space, Perm perm) {
        return permissions(user, space).contains(perm);
    }

    /** Throws 404 when the space is invisible to the user (no leak of its existence), 403 otherwise. */
    public void check(AuthUser user, Space space, Perm perm) {
        Set<Perm> perms = permissions(user, space);
        if (!perms.contains(Perm.BROWSE)) throw Errors.notFound("Espace introuvable");
        if (!perms.contains(perm)) throw Errors.forbidden("Action non autorisée dans cet espace");
    }

    /**
     * Active users holding BE_ASSIGNABLE in the space through an explicit membership (direct or group).
     * Global admins are not implicitly assignable everywhere.
     */
    public List<User> assignableUsers(Space space) {
        Map<UUID, User> result = new LinkedHashMap<>();
        for (SpaceMembership m : memberships.findBySpaceId(space.getId())) {
            if (!m.getRole().getPermissions().contains(Perm.BE_ASSIGNABLE)) continue;
            if (m.getUser() != null) result.put(m.getUser().getId(), m.getUser());
            if (m.getGroup() != null) m.getGroup().getMembers().forEach(u -> result.put(u.getId(), u));
        }
        return result.values().stream().filter(User::isActive)
                .sorted(Comparator.comparing(User::getDisplayName, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public boolean isAssignable(Space space, UUID userId) {
        return assignableUsers(space).stream().anyMatch(u -> u.getId().equals(userId));
    }

    /** Ids of spaces the user can browse, with their permissions (archived spaces included). */
    public Map<UUID, Set<Perm>> visibleSpaces(AuthUser user) {
        Map<UUID, Set<Perm>> result = new HashMap<>();
        if (user.admin()) {
            spaces.findAll().forEach(s -> result.put(s.getId(), s.isArchived() ? EnumSet.copyOf(Perm.ARCHIVED) : EnumSet.copyOf(Perm.ALL)));
            return result;
        }
        for (SpaceMembership m : memberships.findAllForUser(user.id())) {
            result.computeIfAbsent(m.getSpace().getId(), k -> EnumSet.noneOf(Perm.class)).addAll(m.getRole().getPermissions());
        }
        result.values().removeIf(p -> !p.contains(Perm.BROWSE));
        return result;
    }
}
