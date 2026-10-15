package com.fluxo.auth;

import com.fluxo.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Clock;
import java.time.Duration;

@Configuration
class SecurityConfig {

    static final String REMEMBER_ME_ATTR = "fluxo.rememberMe";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, TokenBasedRememberMeServices rememberMe,
                                    UserRepository users, ApiTokenService tokens) throws Exception {
        http
                // Token calls carry no cookie, so CSRF does not apply to them.
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers(ApiTokenFilter::hasBearer))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/login", "/api/setup", "/api/setup/**", "/actuator/health", "/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()) // SPA static files
                // Defence in depth against XSS and clickjacking (documentation/SECURITY-ARCHITECTURE.md).
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data: blob:; connect-src 'self'; font-src 'self' data:; object-src 'none'; base-uri 'self'; "
                                + "form-action 'self'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .permissionsPolicyHeader(pp -> pp.policy("camera=(), microphone=(), geolocation=(), display-capture=(self)")))
                .rememberMe(r -> r.rememberMeServices(rememberMe))
                .securityContext(c -> c.securityContextRepository(securityContextRepository()))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .logout(l -> l.logoutUrl("/api/auth/logout")
                        .addLogoutHandler(rememberMe)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()))
                .addFilterBefore(new ApiTokenFilter(tokens), AuthorizationFilter.class)
                .addFilterBefore(new CurrentUserFilter(users), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    UserDetailsService userDetailsService(UserRepository users) {
        return username -> users.findByUsernameIgnoreCase(username)
                .map(u -> org.springframework.security.core.userdetails.User.withUsername(u.getUsername())
                        .password(u.getPasswordHash())
                        .disabled(!u.isActive())
                        .roles(u.isAdmin() ? new String[]{"USER", "ADMIN"} : new String[]{"USER"})
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }

    /** "Remember me" for 30 days; the token signature includes the password hash, so a password change revokes it. */
    @Bean
    TokenBasedRememberMeServices rememberMeServices(@Value("${fluxo.secret}") String secret, UserDetailsService uds) {
        if (secret.isBlank()) throw new IllegalStateException("FLUXO_SECRET must be set to a long random value");
        var services = new TokenBasedRememberMeServices(secret, uds) {
            @Override
            protected boolean rememberMeRequested(HttpServletRequest request, String parameter) {
                return Boolean.TRUE.equals(request.getAttribute(REMEMBER_ME_ATTR));
            }

            @Override
            protected String retrieveUserName(Authentication authentication) {
                return authentication.getName(); // AuthUser is a Principal, not a UserDetails
            }
        };
        services.setTokenValiditySeconds((int) Duration.ofDays(30).toSeconds());
        services.setCookieName("fluxo-remember");
        return services;
    }
}
