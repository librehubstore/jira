package com.fluxo.collab;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.ticket.Ticket;
import com.fluxo.ticket.TicketService;
import com.fluxo.ticket.TicketViews.TicketRef;
import com.fluxo.ticket.TicketViews.UserRef;
import com.fluxo.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Comments, reactions, attachments, watchers and links of a ticket (spec §4.4–4.7). */
@RestController
@RequestMapping("/api")
class CollabController {

    private final CommentService comments;
    private final AttachmentService attachments;
    private final WatcherService watchers;
    private final LinkService links;
    private final LinkTypeRepository linkTypes;
    private final TicketService tickets;
    private final PermissionService permissions;
    private final UserRepository users;

    CollabController(CommentService comments, AttachmentService attachments, WatcherService watchers, LinkService links,
                     LinkTypeRepository linkTypes, TicketService tickets, PermissionService permissions, UserRepository users) {
        this.comments = comments;
        this.attachments = attachments;
        this.watchers = watchers;
        this.links = links;
        this.linkTypes = linkTypes;
        this.tickets = tickets;
        this.permissions = permissions;
        this.users = users;
    }

    // --- Comments -------------------------------------------------------------

    /** {@code visibility}: absent = space default, "" = everyone, else a space role id. */
    record CommentBody(@NotBlank String body, String visibility) {}

    record CommentView(UUID id, UserRef author, String body, Instant createdAt, Instant editedAt, UUID visibilityRoleId,
                       List<CommentService.ReactionCount> reactions, boolean canEdit, boolean canDelete) {}

    record ReactionBody(@NotBlank String emoji) {}

    @GetMapping("/tickets/{key}/comments")
    @Transactional(readOnly = true)
    List<CommentView> comments(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        List<Comment> list = comments.list(me, key);
        if (list.isEmpty()) return List.of();
        var reactions = comments.reactions(list.stream().map(Comment::getId).toList(), me.id());
        var perms = permissions.permissions(me, list.getFirst().getTicket().getSpace());
        return list.stream().map(c -> view(c, reactions.getOrDefault(c.getId(), List.of()), me, perms)).toList();
    }

    @PostMapping("/tickets/{key}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    CommentView addComment(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @Valid @RequestBody CommentBody req) {
        Comment c = comments.add(me, key, req.body(), req.visibility()).comment();
        return view(c, List.of(), me, permissions.permissions(me, c.getTicket().getSpace()));
    }

    @PatchMapping("/comments/{id}")
    @Transactional
    CommentView editComment(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @Valid @RequestBody CommentBody req) {
        Comment c = comments.edit(me, id, req.body()).comment();
        return view(c, comments.reactions(List.of(c.getId()), me.id()).getOrDefault(c.getId(), List.of()), me,
                permissions.permissions(me, c.getTicket().getSpace()));
    }

    @DeleteMapping("/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteComment(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        comments.delete(me, id);
    }

    @PostMapping("/comments/{id}/reactions")
    void react(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @Valid @RequestBody ReactionBody req) {
        comments.toggleReaction(me, id, req.emoji());
    }

    private static CommentView view(Comment c, List<CommentService.ReactionCount> reactions, AuthUser me, java.util.Set<Perm> perms) {
        boolean own = c.getAuthor().getId().equals(me.id());
        return new CommentView(c.getId(), UserRef.of(c.getAuthor()), c.getBody(), c.getCreatedAt(), c.getEditedAt(), c.getVisibilityRoleId(), reactions,
                perms.contains(own ? Perm.COMMENT_EDIT_OWN : Perm.COMMENT_EDIT_ALL),
                perms.contains(own ? Perm.COMMENT_DELETE_OWN : Perm.COMMENT_DELETE_ALL));
    }

    // --- Attachments ----------------------------------------------------------

    record AttachmentView(UUID id, String filename, String mime, long size, boolean hasThumbnail, boolean inline,
                          UserRef uploader, Instant createdAt, boolean canDelete) {}

    @GetMapping("/tickets/{key}/attachments")
    @Transactional(readOnly = true)
    List<AttachmentView> attachments(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        List<Attachment> list = attachments.list(me, key);
        if (list.isEmpty()) return List.of();
        var perms = permissions.permissions(me, list.getFirst().getTicket().getSpace());
        return list.stream().map(a -> view(a, me, perms)).toList();
    }

    @PostMapping(path = "/tickets/{key}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    AttachmentView upload(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @RequestParam MultipartFile file) {
        Attachment a = attachments.upload(me, key, file);
        return view(a, me, permissions.permissions(me, a.getTicket().getSpace()));
    }

    /** Images and PDFs are shown inline; everything else downloads. Sandboxed so a file can never run script. */
    @GetMapping("/attachments/{id}")
    ResponseEntity<FileSystemResource> download(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id,
                                                @RequestParam(defaultValue = "false") boolean thumbnail) {
        Attachment a = attachments.get(me, id);
        if (thumbnail && a.getThumbnailKey() == null) throw Errors.notFound("Pas de vignette");
        boolean inline = thumbnail || AttachmentService.INLINE_TYPES.contains(a.getMime());
        String type = thumbnail ? "image/png" : inline ? a.getMime() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        var file = attachments.file(thumbnail ? a.getThumbnailKey() : a.getStorageKey());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                        .filename(a.getFilename(), StandardCharsets.UTF_8).build().toString())
                .header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self'; style-src 'unsafe-inline'")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(new FileSystemResource(file));
    }

    record RenameBody(@NotBlank String filename) {}

    @PatchMapping("/attachments/{id}")
    @Transactional
    void renameAttachment(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id, @Valid @RequestBody RenameBody req) {
        attachments.rename(me, id, req.filename());
    }

    @DeleteMapping("/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAttachment(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        attachments.delete(me, id);
    }

    private static AttachmentView view(Attachment a, AuthUser me, java.util.Set<Perm> perms) {
        boolean own = a.getUploader().getId().equals(me.id());
        return new AttachmentView(a.getId(), a.getFilename(), a.getMime(), a.getSize(), a.getThumbnailKey() != null,
                AttachmentService.INLINE_TYPES.contains(a.getMime()), UserRef.of(a.getUploader()), a.getCreatedAt(),
                perms.contains(own ? Perm.ATTACH_DELETE_OWN : Perm.ATTACH_DELETE_ALL));
    }

    // --- Watchers -------------------------------------------------------------

    record WatchersView(List<UserRef> watchers, boolean watching, boolean canManage) {}

    record WatcherBody(@NotNull UUID userId) {}

    @GetMapping("/tickets/{key}/watchers")
    @Transactional(readOnly = true)
    WatchersView watchers(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        Ticket t = tickets.get(me, key);
        var list = watchers.watchers(t.getId());
        return new WatchersView(list.stream().map(UserRef::of).toList(),
                list.stream().anyMatch(u -> u.getId().equals(me.id())),
                permissions.has(me, t.getSpace(), Perm.MANAGE_WATCHERS));
    }

    @PutMapping("/tickets/{key}/watch")
    @Transactional
    void watch(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        watchers.watch(tickets.get(me, key).getId(), me.id());
    }

    @DeleteMapping("/tickets/{key}/watch")
    @Transactional
    void unwatch(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        watchers.unwatch(tickets.get(me, key).getId(), me.id());
    }

    @PostMapping("/tickets/{key}/watchers")
    @Transactional
    void addWatcher(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @Valid @RequestBody WatcherBody req) {
        Ticket t = tickets.get(me, key);
        permissions.check(me, t.getSpace(), Perm.MANAGE_WATCHERS);
        var u = users.findById(req.userId()).filter(x -> x.isActive())
                .orElseThrow(() -> Errors.badRequest("INVALID_USER", "Utilisateur introuvable"));
        if (!permissions.has(AuthUser.of(u), t.getSpace(), Perm.BROWSE)) {
            throw Errors.badRequest("NOT_MEMBER", "Cette personne ne voit pas ce ticket");
        }
        watchers.watch(t.getId(), u.getId());
    }

    @DeleteMapping("/tickets/{key}/watchers/{userId}")
    @Transactional
    void removeWatcher(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @PathVariable UUID userId) {
        Ticket t = tickets.get(me, key);
        if (!userId.equals(me.id())) permissions.check(me, t.getSpace(), Perm.MANAGE_WATCHERS);
        watchers.unwatch(t.getId(), userId);
    }

    // --- Links ----------------------------------------------------------------

    record LinkTypeView(UUID id, String name, String outward, String inward) {}

    record LinkBody(@NotNull UUID linkTypeId, @NotBlank String ticketKey, Boolean inward) {}

    /** One row per link as seen from this ticket; restricted when the other side is not visible. */
    record LinkView(UUID id, UUID linkTypeId, String label, boolean restricted, TicketRef ticket) {}

    record WebLinkBody(@NotBlank String url, String title) {}

    record WebLinkView(UUID id, String url, String title) {}

    record LinksView(List<LinkView> links, List<WebLinkView> webLinks) {}

    @GetMapping("/link-types")
    List<LinkTypeView> linkTypes() {
        return linkTypes.findAllByOrderByNameAsc().stream()
                .map(l -> new LinkTypeView(l.getId(), l.getName(), l.getOutward(), l.getInward())).toList();
    }

    @GetMapping("/tickets/{key}/links")
    @Transactional(readOnly = true)
    LinksView links(@AuthenticationPrincipal AuthUser me, @PathVariable String key) {
        Ticket t = tickets.get(me, key);
        List<LinkView> list = links.list(me, key).stream().map(l -> {
            boolean outward = l.getSource().getId().equals(t.getId());
            Ticket other = outward ? l.getTarget() : l.getSource();
            boolean visible = permissions.has(me, other.getSpace(), Perm.BROWSE);
            return new LinkView(l.getId(), l.getLinkType().getId(),
                    outward ? l.getLinkType().getOutward() : l.getLinkType().getInward(),
                    !visible, visible ? TicketRef.of(other) : null);
        }).toList();
        return new LinksView(list, links.webLinks(me, key).stream()
                .map(w -> new WebLinkView(w.getId(), w.getUrl(), w.getTitle())).toList());
    }

    @PostMapping("/tickets/{key}/links")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    void link(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @Valid @RequestBody LinkBody req) {
        links.link(me, key, req.linkTypeId(), req.ticketKey(), Boolean.TRUE.equals(req.inward()));
    }

    @DeleteMapping("/links/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        links.unlink(me, id);
    }

    @PostMapping("/tickets/{key}/web-links")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    WebLinkView addWebLink(@AuthenticationPrincipal AuthUser me, @PathVariable String key, @Valid @RequestBody WebLinkBody req) {
        var w = links.addWebLink(me, key, req.url(), req.title());
        return new WebLinkView(w.getId(), w.getUrl(), w.getTitle());
    }

    @DeleteMapping("/web-links/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteWebLink(@AuthenticationPrincipal AuthUser me, @PathVariable UUID id) {
        links.deleteWebLink(me, id);
    }
}
