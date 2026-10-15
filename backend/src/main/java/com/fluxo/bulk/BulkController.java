package com.fluxo.bulk;

import com.fluxo.auth.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/bulk")
class BulkController {

    private final BulkService service;

    BulkController(BulkService service) {
        this.service = service;
    }

    /** Starts the job and answers at once (202); progress via GET (spec §4.12: no browser timeout). */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    BulkJob start(@AuthenticationPrincipal AuthUser me, @RequestBody BulkService.Request req) {
        return service.start(me, req);
    }

    @GetMapping("/{id}")
    BulkJob get(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        return service.get(me, id);
    }
}
