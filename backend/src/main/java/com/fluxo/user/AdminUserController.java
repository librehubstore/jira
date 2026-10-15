package com.fluxo.user;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Account and group administration (global admins only, see SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
class AdminUserController {

    private final UserService userService;
    private final UserRepository users;
    private final GroupRepository groups;

    AdminUserController(UserService userService, UserRepository users, GroupRepository groups) {
        this.userService = userService;
        this.users = users;
        this.groups = groups;
    }

    record UserDto(UUID id, String username, String displayName, String email, boolean admin, boolean active,
                   boolean mustChangePassword, boolean locked, Instant createdAt) {
        static UserDto of(User u) {
            return new UserDto(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), u.isAdmin(),
                    u.isActive(), u.isMustChangePassword(), u.isLocked(Instant.now()), u.getCreatedAt());
        }
    }

    record CreateUser(@NotBlank String username, @NotBlank @Size(max = 128) String displayName,
                      @Email String email, @NotBlank String temporaryPassword, Boolean admin) {}

    record UpdateUser(@Size(max = 128) String displayName, String email, Boolean admin, Boolean active) {}

    record ResetPassword(@NotBlank String temporaryPassword) {}

    record GroupDto(UUID id, String name, List<UUID> memberIds) {
        static GroupDto of(Group g) {
            return new GroupDto(g.getId(), g.getName(), g.getMembers().stream().map(User::getId).toList());
        }
    }

    record GroupRequest(@NotBlank @Size(max = 128) String name) {}

    @GetMapping("/users")
    List<UserDto> listUsers() {
        return users.findAllByOrderByDisplayNameAsc().stream().map(UserDto::of).toList();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    UserDto createUser(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CreateUser req) {
        return UserDto.of(userService.create(me.id(), req.username(), req.displayName(), req.email(),
                req.temporaryPassword(), Boolean.TRUE.equals(req.admin()), true));
    }

    @PatchMapping("/users/{id}")
    UserDto updateUser(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @Valid @RequestBody UpdateUser req) {
        return UserDto.of(userService.update(me.id(), id, req.displayName(), req.email(), req.admin(), req.active()));
    }

    @PostMapping("/users/{id}/reset-password")
    void resetPassword(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @Valid @RequestBody ResetPassword req) {
        userService.resetPassword(me.id(), id, req.temporaryPassword());
    }

    @PostMapping("/users/{id}/unlock")
    void unlock(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        userService.unlock(me.id(), id);
    }

    @GetMapping("/groups")
    @Transactional(readOnly = true)
    List<GroupDto> listGroups() {
        return groups.findAllByOrderByNameAsc().stream().map(GroupDto::of).toList();
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    GroupDto createGroup(@Valid @RequestBody GroupRequest req) {
        if (groups.existsByNameIgnoreCase(req.name())) throw Errors.conflict("GROUP_EXISTS", "Ce groupe existe déjà");
        return GroupDto.of(groups.save(new Group(req.name().strip())));
    }

    @PatchMapping("/groups/{id}")
    @Transactional
    GroupDto renameGroup(@PathVariable UUID id, @Valid @RequestBody GroupRequest req) {
        Group g = group(id);
        g.setName(req.name().strip());
        return GroupDto.of(g);
    }

    @PutMapping("/groups/{id}/members")
    @Transactional
    GroupDto setMembers(@PathVariable UUID id, @RequestBody Set<UUID> userIds) {
        Group g = group(id);
        g.getMembers().clear();
        g.getMembers().addAll(users.findAllById(userIds));
        return GroupDto.of(g);
    }

    @DeleteMapping("/groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteGroup(@PathVariable UUID id) {
        groups.delete(group(id));
    }

    private Group group(UUID id) {
        return groups.findById(id).orElseThrow(() -> Errors.notFound("Groupe introuvable"));
    }
}
