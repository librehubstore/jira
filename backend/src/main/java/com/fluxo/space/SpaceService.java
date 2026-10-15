package com.fluxo.space;

import com.fluxo.auth.AuthUser;
import com.fluxo.board.Board;
import com.fluxo.board.BoardRepository;
import com.fluxo.common.AuditService;
import com.fluxo.common.Errors;
import com.fluxo.ticket.TicketTypeRepository;
import com.fluxo.user.Group;
import com.fluxo.user.GroupRepository;
import com.fluxo.user.User;
import com.fluxo.user.UserRepository;
import com.fluxo.workflow.Status;
import com.fluxo.workflow.StatusCategory;
import com.fluxo.workflow.Workflow;
import com.fluxo.workflow.WorkflowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class SpaceService {

    static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9]{1,9}");
    static final String ROLE_ADMIN = "Admin", ROLE_MEMBER = "Membre", ROLE_VIEWER = "Lecteur";

    private final SpaceRepository spaces;
    private final SpaceKeyAliasRepository aliases;
    private final SpaceRoleRepository roles;
    private final SpaceMembershipRepository memberships;
    private final WorkflowRepository workflows;
    private final TicketTypeRepository ticketTypes;
    private final BoardRepository boards;
    private final UserRepository users;
    private final GroupRepository groups;
    private final PermissionService permissions;
    private final AuditService audit;

    SpaceService(SpaceRepository spaces, SpaceKeyAliasRepository aliases, SpaceRoleRepository roles,
                 SpaceMembershipRepository memberships, WorkflowRepository workflows, TicketTypeRepository ticketTypes,
                 BoardRepository boards, UserRepository users, GroupRepository groups,
                 PermissionService permissions, AuditService audit) {
        this.spaces = spaces;
        this.aliases = aliases;
        this.roles = roles;
        this.memberships = memberships;
        this.workflows = workflows;
        this.ticketTypes = ticketTypes;
        this.boards = boards;
        this.users = users;
        this.groups = groups;
        this.permissions = permissions;
        this.audit = audit;
    }

    /** Creates a ready-to-use space: simplified workflow, default types, roles and a Kanban board. */
    public Space create(AuthUser actor, String key, String name, String description, UUID leadId) {
        if (!actor.admin()) throw Errors.forbidden("Seul un administrateur peut créer un espace");
        checkKeyAvailable(key, null);
        Workflow wf = new Workflow("Workflow " + key);
        Status todo = wf.addStatus("À faire", StatusCategory.TODO);
        Status doing = wf.addStatus("En cours", StatusCategory.IN_PROGRESS);
        Status done = wf.addStatus("Terminé", StatusCategory.DONE);
        workflows.save(wf);

        Space space = new Space(key, name.strip(), wf);
        space.setDescription(description);
        User lead = users.findById(leadId != null ? leadId : actor.id())
                .orElseThrow(() -> Errors.badRequest("INVALID_LEAD", "Responsable introuvable"));
        space.setLead(lead);
        space.getTicketTypes().addAll(ticketTypes.findAll());
        spaces.save(space);

        SpaceRole admin = roles.save(new SpaceRole(space, ROLE_ADMIN, Perm.ALL));
        roles.save(new SpaceRole(space, ROLE_MEMBER, Perm.MEMBER));
        roles.save(new SpaceRole(space, ROLE_VIEWER, Perm.VIEWER));
        memberships.save(new SpaceMembership(space, lead, null, admin));

        Board board = new Board(space, "Board " + space.getName(), true);
        board.addColumn(todo.getName()).getStatuses().add(todo);
        board.addColumn(doing.getName()).getStatuses().add(doing);
        board.addColumn(done.getName()).getStatuses().add(done);
        board.setHideDoneDays(14);
        boards.save(board);

        audit.log(actor.id(), "space.create", key);
        return space;
    }

    /** Resolves a key or an old alias, and checks the user can browse the space. */
    @Transactional(readOnly = true)
    public Space get(AuthUser user, String key) {
        Space s = spaces.findByKey(key).or(() -> spaces.findByAlias(key))
                .orElseThrow(() -> Errors.notFound("Espace introuvable"));
        permissions.check(user, s, Perm.BROWSE);
        return s;
    }

    /** Estimation unit of the space (spec §4.3): POINTS (default) or HOURS. */
    public static String estimateUnit(Space s) {
        return "HOURS".equals(s.getSettings().get("estimateUnit")) ? "HOURS" : "POINTS";
    }

    public void setEstimateUnit(AuthUser user, Space s, String unit) {
        permissions.check(user, s, Perm.ADMINISTER_SPACE);
        if (!Set.of("POINTS", "HOURS").contains(unit)) throw Errors.badRequest("INVALID_UNIT", "Unité POINTS ou HOURS");
        Map<String, Object> settings = new java.util.HashMap<>(s.getSettings());
        settings.put("estimateUnit", unit);
        s.setSettings(settings);
    }

    /** Default visibility of new comments: a role of the space, or "" for everyone (spec §4.4). */
    public void setCommentVisibility(AuthUser user, Space s, String roleId) {
        permissions.check(user, s, Perm.ADMINISTER_SPACE);
        if (roleId.isBlank()) {
            s.getSettings().remove("commentVisibilityRoleId");
        } else {
            UUID id;
            try {
                id = UUID.fromString(roleId);
            } catch (IllegalArgumentException e) {
                throw Errors.badRequest("INVALID_ROLE", "Rôle inconnu");
            }
            if (roles.findById(id).filter(r -> r.getSpace().equals(s)).isEmpty()) throw Errors.badRequest("INVALID_ROLE", "Rôle inconnu dans cet espace");
            s.getSettings().put("commentVisibilityRoleId", roleId);
        }
        s.setSettings(new java.util.HashMap<>(s.getSettings())); // new map so the JSON column is seen as dirty
    }

    public Space update(AuthUser user, String key, String name, String description, UUID leadId,
                        String newKey, Boolean archived) {
        Space s = spaces.findByKey(key).or(() -> spaces.findByAlias(key))
                .orElseThrow(() -> Errors.notFound("Espace introuvable"));
        permissions.check(user, s, Perm.ADMINISTER_SPACE); // kept on archived spaces, so they can be restored
        boolean wasArchived = s.isArchived();
        if (wasArchived && !Boolean.FALSE.equals(archived) && (name != null || description != null || leadId != null || newKey != null)) {
            throw Errors.badRequest("SPACE_ARCHIVED", "Espace archivé : désarchivez-le avant de le modifier");
        }
        if (name != null && !name.isBlank()) s.setName(name.strip());
        if (description != null) s.setDescription(description.isBlank() ? null : description);
        if (leadId != null) s.setLead(users.findById(leadId).orElseThrow(() -> Errors.badRequest("INVALID_LEAD", "Responsable introuvable")));
        if (newKey != null && !newKey.equals(s.getKey())) {
            checkKeyAvailable(newKey, s);
            aliases.findById(newKey).ifPresent(aliases::delete); // reclaiming one of its own old keys
            aliases.save(new SpaceKeyAlias(s.getKey(), s));
            audit.log(user.id(), "space.rename_key", s.getKey() + " -> " + newKey);
            s.setKey(newKey);
        }
        if (archived != null && archived != s.isArchived()) {
            s.setArchived(archived);
            audit.log(user.id(), archived ? "space.archive" : "space.unarchive", s.getKey());
        }
        return s;
    }

    public void delete(AuthUser user, String key, String confirmKey) {
        if (!user.admin()) throw Errors.forbidden("Seul un administrateur peut supprimer un espace");
        Space s = spaces.findByKey(key).orElseThrow(() -> Errors.notFound("Espace introuvable"));
        if (!key.equals(confirmKey)) throw Errors.badRequest("CONFIRMATION_MISMATCH", "Saisissez la clé de l'espace pour confirmer");
        Workflow wf = s.getWorkflow();
        spaces.delete(s);
        spaces.flush();
        if (!wf.isShared()) workflows.delete(wf);
        audit.log(user.id(), "space.delete", key);
    }

    // --- Members and roles -------------------------------------------------

    public SpaceMembership addMember(AuthUser user, String key, UUID userId, UUID groupId, UUID roleId) {
        Space s = get(user, key);
        permissions.check(user, s, Perm.ADMINISTER_SPACE);
        if ((userId == null) == (groupId == null)) throw Errors.badRequest("INVALID_MEMBER", "Indiquez un utilisateur ou un groupe");
        SpaceRole role = role(s, roleId);
        User u = userId == null ? null : users.findById(userId).filter(User::isActive)
                .orElseThrow(() -> Errors.badRequest("INVALID_MEMBER", "Utilisateur introuvable"));
        Group g = groupId == null ? null : groups.findById(groupId)
                .orElseThrow(() -> Errors.badRequest("INVALID_MEMBER", "Groupe introuvable"));
        boolean exists = memberships.findBySpaceId(s.getId()).stream().anyMatch(m ->
                (u != null && m.getUser() != null && m.getUser().getId().equals(u.getId()))
                        || (g != null && m.getGroup() != null && m.getGroup().getId().equals(g.getId())));
        if (exists) throw Errors.conflict("ALREADY_MEMBER", "Déjà membre de l'espace");
        return memberships.save(new SpaceMembership(s, u, g, role));
    }

    public SpaceMembership changeMemberRole(AuthUser user, String key, UUID membershipId, UUID roleId) {
        Space s = get(user, key);
        permissions.check(user, s, Perm.ADMINISTER_SPACE);
        SpaceMembership m = membership(s, membershipId);
        m.setRole(role(s, roleId));
        return m;
    }

    public void removeMember(AuthUser user, String key, UUID membershipId) {
        Space s = get(user, key);
        permissions.check(user, s, Perm.ADMINISTER_SPACE);
        memberships.delete(membership(s, membershipId));
    }

    public SpaceRole updateRole(AuthUser user, String key, UUID roleId, Set<Perm> perms) {
        Space s = get(user, key);
        permissions.check(user, s, Perm.ADMINISTER_SPACE);
        SpaceRole r = role(s, roleId);
        if (!perms.contains(Perm.BROWSE) && !perms.isEmpty()) perms.add(Perm.BROWSE);
        r.setPermissions(perms);
        audit.log(user.id(), "space.role_update", s.getKey() + "/" + r.getName());
        return r;
    }

    private SpaceRole role(Space s, UUID roleId) {
        return roles.findById(roleId).filter(r -> r.getSpace().getId().equals(s.getId()))
                .orElseThrow(() -> Errors.badRequest("INVALID_ROLE", "Rôle introuvable"));
    }

    private SpaceMembership membership(Space s, UUID id) {
        return memberships.findById(id).filter(m -> m.getSpace().getId().equals(s.getId()))
                .orElseThrow(() -> Errors.notFound("Membre introuvable"));
    }

    /** A key is free unless used by a space, or kept as an alias by another space (it must keep redirecting). */
    private void checkKeyAvailable(String key, Space self) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw Errors.badRequest("INVALID_KEY", "Clé invalide : une majuscule puis 1 à 9 majuscules ou chiffres (ex. PAY)");
        }
        boolean aliasOfOther = aliases.findById(key).map(a -> self == null || !a.getSpace().getId().equals(self.getId())).orElse(false);
        if (spaces.existsByKey(key) || aliasOfOther) throw Errors.conflict("KEY_TAKEN", "Cette clé est déjà utilisée");
    }
}
