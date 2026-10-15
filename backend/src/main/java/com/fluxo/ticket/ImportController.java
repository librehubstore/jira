package com.fluxo.ticket;

import com.fluxo.auth.AuthUser;
import com.fluxo.collab.CommentService;
import com.fluxo.collab.LinkService;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.space.Space;
import com.fluxo.space.SpaceRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** CSV import (preview, simulation) and JSON export of a ticket (spec §8). */
@RestController
@RequestMapping("/api")
class ImportController {

    private final ImportService service;
    private final SpaceRepository spaces;
    private final PermissionService permissions;
    private final TicketService tickets;
    private final TicketRepository repo;
    private final CommentService comments;
    private final LinkService links;
    private final HistoryRepository history;
    private final JsonMapper json;

    ImportController(ImportService service, SpaceRepository spaces, PermissionService permissions, TicketService tickets,
                     TicketRepository repo, CommentService comments, LinkService links, HistoryRepository history, JsonMapper json) {
        this.service = service;
        this.spaces = spaces;
        this.permissions = permissions;
        this.tickets = tickets;
        this.repo = repo;
        this.comments = comments;
        this.links = links;
        this.history = history;
        this.json = json;
    }

    @PostMapping("/spaces/{key}/import/preview")
    ImportService.Preview preview(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestParam MultipartFile file) throws IOException {
        space(me, key);
        return service.preview(new String(file.getBytes(), StandardCharsets.UTF_8));
    }

    @PostMapping(path = "/spaces/{key}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportService.Report run(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestParam MultipartFile file,
                             @RequestParam String options) throws IOException {
        Space s = space(me, key);
        return service.run(me, s, new String(file.getBytes(), StandardCharsets.UTF_8), json.readValue(options, ImportService.Options.class));
    }

    /** One ticket with its comments (those the user may read), links and history. */
    @GetMapping("/tickets/{key}/export.json")
    @Transactional(readOnly = true)
    ResponseEntity<Map<String, Object>> export(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        Ticket t = tickets.get(me, key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticket", TicketViews.TicketDetail.of(t, repo.findChildren(t.getId()), permissions.permissions(me, t.getSpace())));
        out.put("comments", comments.list(me, key).stream().map(c -> Map.of("author", c.getAuthor().getUsername(), "body", c.getBody(),
                "createdAt", c.getCreatedAt().toString())).toList());
        out.put("links", links.list(me, key).stream().map(l -> Map.of("type", l.getLinkType().getName(), "source", l.getSource().getKey(),
                "target", l.getTarget().getKey())).toList());
        out.put("history", history.findByTicket(t.getId()).stream().map(TicketViews.HistoryView::of).toList());
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + t.getKey() + ".json\"").body(out);
    }

    private Space space(AuthUser me, String key) {
        Space s = spaces.findByKey(key).orElseThrow(() -> Errors.notFound("Espace introuvable"));
        permissions.check(me, s, Perm.CREATE);
        return s;
    }
}
