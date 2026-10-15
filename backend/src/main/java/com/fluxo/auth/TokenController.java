package com.fluxo.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/me/tokens")
class TokenController {

    private final ApiTokenService service;

    TokenController(ApiTokenService service) {
        this.service = service;
    }

    record CreateToken(String name, ApiTokenService.Scope scope, Instant expiresAt) {}

    @GetMapping
    List<ApiTokenService.TokenView> list(@AuthenticationPrincipal AuthUser me) {
        return service.list(me);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiTokenService.Created create(@AuthenticationPrincipal AuthUser me, @RequestBody CreateToken req) {
        return service.create(me, req.name(), req.scope(), req.expiresAt());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        service.revoke(me, id);
    }
}
