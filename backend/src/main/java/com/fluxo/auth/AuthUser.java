package com.fluxo.auth;

import com.fluxo.user.User;

import java.security.Principal;
import java.util.UUID;

/** Authenticated principal, refreshed from the database on every request. */
public record AuthUser(UUID id, String username, String displayName, boolean admin) implements Principal {

    public static AuthUser of(User u) {
        return new AuthUser(u.getId(), u.getUsername(), u.getDisplayName(), u.isAdmin());
    }

    @Override
    public String getName() {
        return username;
    }
}
