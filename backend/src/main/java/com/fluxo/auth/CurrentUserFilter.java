package com.fluxo.auth;

import com.fluxo.user.User;
import com.fluxo.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Reloads the user on every request so that deactivation and role changes apply immediately
 * (closes existing sessions), and blocks the API until a temporary password is changed.
 */
class CurrentUserFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_WITH_TEMP_PASSWORD = Set.of("/api/me", "/api/me/password", "/api/auth/logout");

    private final UserRepository users;

    CurrentUserFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            User u = users.findByUsernameIgnoreCase(auth.getName()).filter(User::isActive).orElse(null);
            if (u == null) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) session.invalidate();
            } else {
                var authorities = u.isAdmin()
                        ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                        : List.of(new SimpleGrantedAuthority("ROLE_USER"));
                var refreshed = UsernamePasswordAuthenticationToken.authenticated(AuthUser.of(u), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(refreshed);
                if (u.isMustChangePassword() && request.getRequestURI().startsWith("/api/")
                        && !ALLOWED_WITH_TEMP_PASSWORD.contains(request.getRequestURI())) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/problem+json");
                    response.getWriter().write("{\"status\":403,\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"detail\":\"Vous devez changer votre mot de passe temporaire\"}");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
