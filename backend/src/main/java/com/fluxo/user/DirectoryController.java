package com.fluxo.user;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Active users and groups, for pickers (any authenticated user). */
@RestController
@RequestMapping("/api/directory")
class DirectoryController {

    private final UserRepository users;
    private final GroupRepository groups;

    DirectoryController(UserRepository users, GroupRepository groups) {
        this.users = users;
        this.groups = groups;
    }

    record Entry(UUID id, String username, String displayName, String avatar) {}

    record GroupEntry(UUID id, String name) {}

    @GetMapping("/users")
    List<Entry> users() {
        return users.findAllByOrderByDisplayNameAsc().stream().filter(User::isActive)
                .map(u -> new Entry(u.getId(), u.getUsername(), u.getDisplayName(), u.avatarUrl())).toList();
    }

    @GetMapping("/groups")
    @Transactional(readOnly = true)
    List<GroupEntry> groups() {
        return groups.findAllByOrderByNameAsc().stream().map(g -> new GroupEntry(g.getId(), g.getName())).toList();
    }
}
