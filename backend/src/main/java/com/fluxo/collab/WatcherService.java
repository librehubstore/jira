package com.fluxo.collab;

import com.fluxo.user.User;
import com.fluxo.user.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Ticket watchers (spec §4.7). A plain join table: no entity needed. */
@Service
@Transactional
public class WatcherService {

    private final JdbcClient jdbc;
    private final UserRepository users;

    WatcherService(JdbcClient jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public void watch(UUID ticketId, UUID userId) {
        jdbc.sql("insert into watchers (ticket_id, user_id) values (?, ?) on conflict do nothing")
                .params(ticketId, userId).update();
    }

    public void unwatch(UUID ticketId, UUID userId) {
        jdbc.sql("delete from watchers where ticket_id = ? and user_id = ?").params(ticketId, userId).update();
    }

    /** Watch after commenting or editing, unless the user turned the "autoWatch" preference off. */
    public void autoWatch(UUID ticketId, UUID userId) {
        users.findById(userId).filter(u -> !Boolean.FALSE.equals(u.getPreferences().get("autoWatch")))
                .ifPresent(u -> watch(ticketId, userId));
    }

    @Transactional(readOnly = true)
    public List<UUID> watcherIds(UUID ticketId) {
        return jdbc.sql("select user_id from watchers where ticket_id = ?").param(ticketId).query(UUID.class).list();
    }

    @Transactional(readOnly = true)
    public List<User> watchers(UUID ticketId) {
        return users.findAllById(watcherIds(ticketId)).stream()
                .sorted((a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName())).toList();
    }
}
