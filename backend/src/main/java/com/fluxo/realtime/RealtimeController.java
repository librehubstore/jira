package com.fluxo.realtime;

import com.fluxo.auth.AuthUser;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class RealtimeController {

    private final RealtimeService service;

    RealtimeController(RealtimeService service) {
        this.service = service;
    }

    @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@AuthenticationPrincipal AuthUser me) {
        return service.subscribe(me);
    }
}
