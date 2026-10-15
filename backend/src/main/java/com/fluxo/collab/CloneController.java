package com.fluxo.collab;

import com.fluxo.auth.AuthUser;
import com.fluxo.space.PermissionService;
import com.fluxo.ticket.Ticket;
import com.fluxo.ticket.TicketRepository;
import com.fluxo.ticket.TicketViews.TicketDetail;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class CloneController {

    private final CloneService service;
    private final TicketRepository tickets;
    private final PermissionService permissions;

    CloneController(CloneService service, TicketRepository tickets, PermissionService permissions) {
        this.service = service;
        this.tickets = tickets;
        this.permissions = permissions;
    }

    @PostMapping("/api/tickets/{key}/clone")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    TicketDetail cloneTicket(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestBody CloneService.Options options) {
        Ticket copy = service.cloneTicket(me, key, options);
        tickets.flush();
        return TicketDetail.of(copy, tickets.findChildren(copy.getId()), permissions.permissions(me, copy.getSpace()));
    }
}
