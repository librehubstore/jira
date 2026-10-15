package com.fluxo.auth;

import com.fluxo.common.Errors;
import com.fluxo.user.User;
import com.fluxo.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Password check with lockout: 5 failures within 10 minutes lock the account for 15 minutes (spec §4.1). */
@Service
public class LoginService {

    static final int MAX_FAILURES = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final String dummyHash;

    LoginService(UserRepository users, PasswordEncoder encoder, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString()); // equalises timing for unknown users
    }

    // noRollbackFor: failed attempts must be persisted even though we throw.
    @Transactional(noRollbackFor = Exception.class)
    public User authenticate(String username, String password) {
        Instant now = clock.instant();
        User u = users.findByUsernameIgnoreCase(username).orElse(null);
        if (u == null) {
            encoder.matches(password, dummyHash); // same timing as a real check
            throw badCredentials();
        }
        if (u.isLocked(now)) {
            String until = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(u.getLockedUntil());
            throw Errors.of(HttpStatus.UNAUTHORIZED, "ACCOUNT_LOCKED", "Compte verrouillé jusqu'à " + until + " après trop d'échecs");
        }
        if (!encoder.matches(password, u.getPasswordHash())) {
            recordFailure(u, now);
            throw badCredentials();
        }
        if (!u.isActive()) {
            throw Errors.of(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Ce compte est désactivé");
        }
        u.setFailedLogins(0);
        u.setFirstFailedAt(null);
        u.setLockedUntil(null);
        return u;
    }

    private static void recordFailure(User u, Instant now) {
        if (u.getFirstFailedAt() == null || u.getFirstFailedAt().plus(FAILURE_WINDOW).isBefore(now)) {
            u.setFirstFailedAt(now);
            u.setFailedLogins(1);
        } else {
            u.setFailedLogins(u.getFailedLogins() + 1);
        }
        if (u.getFailedLogins() >= MAX_FAILURES) {
            u.setLockedUntil(now.plus(LOCK_DURATION));
        }
    }

    private static RuntimeException badCredentials() {
        return Errors.of(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Identifiant ou mot de passe incorrect");
    }
}
