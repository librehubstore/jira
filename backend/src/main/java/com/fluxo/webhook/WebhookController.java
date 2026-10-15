package com.fluxo.webhook;

import com.fluxo.auth.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/webhooks")
class WebhookController {

    private final WebhookService service;

    WebhookController(WebhookService service) {
        this.service = service;
    }

    record CreateHook(String url, List<String> events, String spaceKey) {}

    record Activation(boolean active) {}

    @GetMapping
    List<WebhookService.Hook> list(@AuthenticationPrincipal AuthUser me) {
        return service.list(me);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    WebhookService.Hook create(@AuthenticationPrincipal AuthUser me, @RequestBody CreateHook req) {
        return service.create(me, req.url(), req.events(), req.spaceKey());
    }

    @PatchMapping("/{id}")
    void activate(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestBody Activation req) {
        service.setActive(me, id, req.active());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        service.delete(me, id);
    }

    @GetMapping("/{id}/deliveries")
    List<WebhookService.Delivery> deliveries(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        return service.deliveries(me, id);
    }
}
