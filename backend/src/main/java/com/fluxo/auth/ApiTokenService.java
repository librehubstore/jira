package com.fluxo.auth;

import com.fluxo.common.Errors;
import com.fluxo.user.User;
import com.fluxo.user.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Personal access tokens for the API (spec §8): revocable, optional expiry, read-only or read-write scope. */
@Service
@Transactional
public class ApiTokenService {

    public static final String PREFIX = "flx_";
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Scope { READ, WRITE }

    public record TokenView(UUID id, String name, String prefix, Scope scope, Instant expiresAt, Instant lastUsedAt, Instant createdAt) {}

    /** Returned once at creation: the clear token is never stored. */
    public record Created(TokenView token, String secret) {}

    record Authenticated(User user, Scope scope) {}

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final Clock clock;

    ApiTokenService(JdbcClient jdbc, UserRepository users, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.clock = clock;
    }

    public Created create(AuthUser me, String name, Scope scope, Instant expiresAt) {
        if (name == null || name.isBlank() || name.strip().length() > 64) throw Errors.badRequest("NAME_REQUIRED", "Nom obligatoire, 64 caractères au plus");
        if (scope == null) throw Errors.badRequest("TOKEN_SCOPE", "Portée READ ou WRITE");
        if (expiresAt != null && expiresAt.isBefore(clock.instant())) throw Errors.badRequest("TOKEN_EXPIRY", "Date d'expiration passée");
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("insert into api_tokens (id, user_id, name, token_hash, prefix, scope, expires_at, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, me.id(), name.strip(), hash(secret), secret.substring(0, 10), scope.name(),
                        expiresAt == null ? null : java.sql.Timestamp.from(expiresAt), java.sql.Timestamp.from(now)).update();
        return new Created(new TokenView(id, name.strip(), secret.substring(0, 10), scope, expiresAt, null, now), secret);
    }

    @Transactional(readOnly = true)
    public List<TokenView> list(AuthUser me) {
        return jdbc.sql("select id, name, prefix, scope, expires_at, last_used_at, created_at from api_tokens where user_id = ? and not revoked order by created_at desc")
                .param(me.id()).query((rs, i) -> new TokenView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("prefix"),
                        Scope.valueOf(rs.getString("scope")), instant(rs.getTimestamp("expires_at")), instant(rs.getTimestamp("last_used_at")),
                        instant(rs.getTimestamp("created_at")))).list();
    }

    public void revoke(AuthUser me, UUID id) {
        if (jdbc.sql("update api_tokens set revoked = true where id = ? and user_id = ?").params(id, me.id()).update() == 0) {
            throw Errors.notFound("Jeton introuvable");
        }
    }

    /** The active user behind a valid, unexpired, unrevoked token. */
    Optional<Authenticated> authenticate(String secret) {
        if (secret == null || !secret.startsWith(PREFIX)) return Optional.empty();
        var row = jdbc.sql("select id, user_id, scope from api_tokens where token_hash = ? and not revoked and (expires_at is null or expires_at > now())")
                .param(hash(secret)).query((rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("scope")})
                .optional();
        if (row.isEmpty()) return Optional.empty();
        jdbc.sql("update api_tokens set last_used_at = now() where id = ?").param(row.get()[0]).update();
        return users.findById((UUID) row.get()[1]).filter(User::isActive)
                .map(u -> new Authenticated(u, Scope.valueOf((String) row.get()[2])));
    }

    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
