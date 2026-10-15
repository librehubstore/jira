package com.fluxo.notification;

import com.fluxo.auth.AuthUser;
import com.fluxo.ticket.TicketViews.UserRef;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
class NotificationController {

    private final NotificationService service;

    NotificationController(NotificationService service) {
        this.service = service;
    }

    record View(UUID id, String event, UserRef actor, Map<String, Object> payload, boolean read, Instant updatedAt) {}

    record Page(List<View> items, long unread) {}

    @GetMapping
    @Transactional(readOnly = true)
    Page list(@AuthenticationPrincipal AuthUser me, @RequestParam(defaultValue = "false") boolean unreadOnly,
              @RequestParam(defaultValue = "30") int limit) {
        return new Page(service.list(me, unreadOnly, limit).stream()
                .map(n -> new View(n.getId(), n.getEvent(), UserRef.of(n.getActor()), n.getPayload(), n.getReadAt() != null, n.getUpdatedAt()))
                .toList(), service.unreadCount(me));
    }

    @GetMapping("/unread-count")
    Map<String, Long> unread(@AuthenticationPrincipal AuthUser me) {
        return Map.of("unread", service.unreadCount(me));
    }

    @PostMapping("/{id}/read")
    void read(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @RequestParam(defaultValue = "true") boolean read) {
        service.markRead(me, id, read);
    }

    @PostMapping("/read-all")
    Map<String, Integer> readAll(@AuthenticationPrincipal AuthUser me) {
        return Map.of("updated", service.markAllRead(me));
    }
}
