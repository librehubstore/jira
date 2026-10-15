package com.fluxo.auth;

import com.fluxo.common.Errors;
import com.fluxo.user.User;
import com.fluxo.user.UserRepository;
import com.fluxo.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
class AuthController {

    private final LoginService loginService;
    private final UserService userService;
    private final UserRepository users;
    private final SecurityContextRepository contextRepository;
    private final TokenBasedRememberMeServices rememberMe;

    AuthController(LoginService loginService, UserService userService, UserRepository users,
                   SecurityContextRepository contextRepository, TokenBasedRememberMeServices rememberMe) {
        this.loginService = loginService;
        this.userService = userService;
        this.users = users;
        this.contextRepository = contextRepository;
        this.rememberMe = rememberMe;
    }

    record SetupStatus(boolean needsSetup) {}

    record SetupRequest(@NotBlank String username, @NotBlank String displayName, @NotBlank String password) {}

    record LoginRequest(@NotBlank String username, @NotBlank String password, Boolean rememberMe) {}

    record PasswordChange(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    record ProfileUpdate(@Size(max = 128) String displayName, String email, Map<String, Object> preferences) {}

    record Me(UUID id, String username, String displayName, String email, boolean admin,
              boolean mustChangePassword, Map<String, Object> preferences, String avatar) {
        static Me of(User u) {
            return new Me(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), u.isAdmin(),
                    u.isMustChangePassword(), u.getPreferences(), u.avatarUrl());
        }
    }

    @GetMapping("/setup")
    SetupStatus setupStatus() {
        return new SetupStatus(!users.existsByAdminTrue());
    }

    /** First launch without FLUXO_ADMIN_* env: create the first administrator. Disabled once an admin exists. */
    @PostMapping("/setup")
    Me setup(@Valid @RequestBody SetupRequest req) {
        synchronized (this) {
            if (users.existsByAdminTrue()) {
                throw Errors.conflict("ALREADY_SET_UP", "Un administrateur existe déjà");
            }
            return Me.of(userService.create(null, req.username(), req.displayName(), null, req.password(), true, false));
        }
    }

    @PostMapping("/auth/login")
    Me login(@Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        User u = loginService.authenticate(req.username(), req.password());
        var authorities = u.isAdmin()
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var auth = UsernamePasswordAuthenticationToken.authenticated(AuthUser.of(u), null, authorities);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        if (request.getSession(false) != null) request.changeSessionId(); // session fixation
        request.getSession(true).setMaxInactiveInterval((int) Duration.ofHours(8).toSeconds());
        contextRepository.saveContext(context, request, response);
        if (Boolean.TRUE.equals(req.rememberMe())) {
            request.setAttribute(SecurityConfig.REMEMBER_ME_ATTR, true);
            rememberMe.loginSuccess(request, response, auth);
        }
        return Me.of(u);
    }

    @GetMapping("/me")
    Me me(@AuthenticationPrincipal AuthUser me) {
        return Me.of(userService.get(me.id()));
    }

    @PatchMapping("/me")
    Me updateMe(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody ProfileUpdate req) {
        return Me.of(userService.updateProfile(me.id(), req.displayName(), req.email(), req.preferences()));
    }

    @PostMapping("/me/password")
    void changePassword(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody PasswordChange req) {
        userService.changeOwnPassword(me.id(), req.currentPassword(), req.newPassword());
    }
}
