package com.fluxo.user;

import com.fluxo.common.AuditService;
import com.fluxo.common.Errors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class UserService {

    public static final int MIN_PASSWORD_LENGTH = 10;
    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9._-]{2,64}");

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    UserService(UserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    public User create(UUID actorId, String username, String displayName, String email, String password,
                       boolean admin, boolean temporary) {
        if (!USERNAME.matcher(username).matches()) {
            throw Errors.badRequest("INVALID_USERNAME", "Identifiant invalide : 2 à 64 caractères parmi lettres, chiffres, . _ -");
        }
        if (users.existsByUsernameIgnoreCase(username)) {
            throw Errors.conflict("USERNAME_TAKEN", "Cet identifiant est déjà utilisé");
        }
        checkPassword(password);
        User u = new User(username, displayName, encoder.encode(password));
        u.setEmail(email);
        u.setAdmin(admin);
        u.setMustChangePassword(temporary);
        users.save(u);
        audit.log(actorId, "user.create", username);
        return u;
    }

    public User get(UUID id) {
        return users.findById(id).orElseThrow(() -> Errors.notFound("Utilisateur introuvable"));
    }

    public User update(UUID actorId, UUID id, String displayName, String email, Boolean admin, Boolean active) {
        User u = get(id);
        if (displayName != null) u.setDisplayName(displayName);
        if (email != null) u.setEmail(email.isBlank() ? null : email);
        if (admin != null && admin != u.isAdmin()) {
            if (!admin && id.equals(actorId)) {
                throw Errors.badRequest("SELF_DEMOTE", "Vous ne pouvez pas retirer vos propres droits d'administrateur");
            }
            u.setAdmin(admin);
            audit.log(actorId, admin ? "user.grant_admin" : "user.revoke_admin", u.getUsername());
        }
        if (active != null && active != u.isActive()) {
            if (!active && id.equals(actorId)) {
                throw Errors.badRequest("SELF_DEACTIVATE", "Vous ne pouvez pas désactiver votre propre compte");
            }
            u.setActive(active);
            audit.log(actorId, active ? "user.activate" : "user.deactivate", u.getUsername());
        }
        return u;
    }

    public void resetPassword(UUID actorId, UUID id, String temporaryPassword) {
        checkPassword(temporaryPassword);
        User u = get(id);
        u.setPasswordHash(encoder.encode(temporaryPassword));
        u.setMustChangePassword(true);
        unlock(u);
        audit.log(actorId, "user.reset_password", u.getUsername());
    }

    public void unlock(UUID actorId, UUID id) {
        User u = get(id);
        unlock(u);
        audit.log(actorId, "user.unlock", u.getUsername());
    }

    public void changeOwnPassword(UUID id, String current, String next) {
        User u = get(id);
        if (!encoder.matches(current, u.getPasswordHash())) {
            throw Errors.badRequest("WRONG_PASSWORD", "Mot de passe actuel incorrect");
        }
        if (encoder.matches(next, u.getPasswordHash())) {
            throw Errors.badRequest("SAME_PASSWORD", "Le nouveau mot de passe doit être différent de l'actuel");
        }
        checkPassword(next);
        u.setPasswordHash(encoder.encode(next));
        u.setMustChangePassword(false);
    }

    public User updateProfile(UUID id, String displayName, String email, Map<String, Object> preferences) {
        User u = get(id);
        if (displayName != null && !displayName.isBlank()) u.setDisplayName(displayName.strip());
        if (email != null) u.setEmail(email.isBlank() ? null : email.strip());
        if (preferences != null) u.getPreferences().putAll(preferences);
        return u;
    }

    private static void unlock(User u) {
        u.setLockedUntil(null);
        u.setFailedLogins(0);
        u.setFirstFailedAt(null);
    }

    static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw Errors.badRequest("WEAK_PASSWORD", "Le mot de passe doit contenir au moins " + MIN_PASSWORD_LENGTH + " caractères");
        }
    }
}
