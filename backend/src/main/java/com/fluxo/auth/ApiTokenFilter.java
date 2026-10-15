package com.fluxo.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * {@code Authorization: Bearer flx_…}: authenticates the request with a personal access token, for this request only
 * (nothing stored in the session). A read-only token may only read.
 */
class ApiTokenFilter extends OncePerRequestFilter {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final ApiTokenService tokens;

    ApiTokenFilter(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    static boolean hasBearer(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        return h != null && h.startsWith("Bearer ");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hasBearer(request)) {
            chain.doFilter(request, response);
            return;
        }
        var auth = tokens.authenticate(request.getHeader("Authorization").substring(7).strip());
        if (auth.isEmpty()) {
            problem(response, 401, "INVALID_TOKEN", "Jeton d'accès invalide, expiré ou révoqué");
            return;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/api/me/tokens") || path.startsWith("/api/me/password")) {
            problem(response, 403, "SESSION_REQUIRED", "Action réservée à une session de connexion");
            return;
        }
        if (auth.get().scope() == ApiTokenService.Scope.READ && !READ_METHODS.contains(request.getMethod())) {
            problem(response, 403, "READ_ONLY_TOKEN", "Ce jeton est en lecture seule");
            return;
        }
        var u = auth.get().user();
        var authorities = u.isAdmin()
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(AuthUser.of(u), null, authorities));
        chain.doFilter(request, response);
    }

    private static void problem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
