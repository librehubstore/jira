package com.fluxo.collab;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.ticket.HistoryEntry;
import com.fluxo.ticket.HistoryRepository;
import com.fluxo.ticket.Ticket;
import com.fluxo.ticket.TicketService;
import com.fluxo.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Files attached to tickets, stored on a local volume (spec §4.5). */
@Service
@Transactional
public class AttachmentService {

    /** Types safe to display inline; everything else (svg, html…) is downloaded. */
    static final Set<String> INLINE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf");
    private static final int THUMB_WIDTH = 240;

    private final AttachmentRepository attachments;
    private final TicketService tickets;
    private final PermissionService permissions;
    private final HistoryRepository history;
    private final UserRepository users;
    private final Path root;
    private final Set<String> blockedExtensions;

    AttachmentService(AttachmentRepository attachments, TicketService tickets, PermissionService permissions,
                      HistoryRepository history, UserRepository users,
                      @Value("${fluxo.storage-dir}") String storageDir,
                      @Value("${fluxo.blocked-extensions:exe,bat,cmd,com,msi,scr,ps1,vbs}") String blocked) throws IOException {
        this.attachments = attachments;
        this.tickets = tickets;
        this.permissions = permissions;
        this.history = history;
        this.users = users;
        this.root = Files.createDirectories(Path.of(storageDir));
        this.blockedExtensions = Arrays.stream(blocked.split(",")).map(s -> s.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    public Attachment upload(AuthUser me, String ticketKey, MultipartFile file) {
        Ticket t = tickets.get(me, ticketKey);
        permissions.check(me, t.getSpace(), Perm.ATTACH_ADD);
        String name = sanitize(file.getOriginalFilename());
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (blockedExtensions.contains(ext)) throw Errors.badRequest("BLOCKED_TYPE", "Type de fichier interdit : ." + ext);
        if (file.isEmpty()) throw Errors.badRequest("EMPTY_FILE", "Fichier vide");
        String key = UUID.randomUUID().toString();
        Path target = root.resolve(key);
        try {
            file.transferTo(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        deleteOnRollback(target);
        String declared = file.getContentType();
        String mime = declared == null ? "application/octet-stream" : declared;
        Attachment a = new Attachment(t, users.getReferenceById(me.id()), name, mime, file.getSize(), key);
        if (mime.startsWith("image/")) a.setThumbnailKey(thumbnail(target, key));
        attachments.save(a);
        history.save(new HistoryEntry(t, users.getReferenceById(me.id()), "attachment", null, a.getId().toString(), null, name));
        return a;
    }

    @Transactional(readOnly = true)
    public Attachment get(AuthUser me, UUID id) {
        Attachment a = attachments.findById(id).orElseThrow(() -> Errors.notFound("Pièce jointe introuvable"));
        if (!permissions.has(me, a.getTicket().getSpace(), Perm.BROWSE)) throw Errors.notFound("Pièce jointe introuvable");
        return a;
    }

    @Transactional(readOnly = true)
    public List<Attachment> list(AuthUser me, String ticketKey) {
        return attachments.findByTicket(tickets.get(me, ticketKey).getId());
    }

    public Path file(String storageKey) {
        return root.resolve(storageKey);
    }

    public void delete(AuthUser me, UUID id) {
        Attachment a = get(me, id);
        boolean own = a.getUploader().getId().equals(me.id());
        permissions.check(me, a.getTicket().getSpace(), own ? Perm.ATTACH_DELETE_OWN : Perm.ATTACH_DELETE_ALL);
        history.save(new HistoryEntry(a.getTicket(), users.getReferenceById(me.id()), "attachment", a.getId().toString(), null,
                a.getFilename(), null));
        attachments.delete(a);
        List<String> keys = a.getThumbnailKey() == null ? List.of(a.getStorageKey()) : List.of(a.getStorageKey(), a.getThumbnailKey());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                keys.forEach(k -> root.resolve(k).toFile().delete());
            }
        });
    }

    /** Rename (spec §4.5, V1): allowed to whoever may delete it; the extension stays checked against blocked types. */
    public Attachment rename(AuthUser me, UUID id, String filename) {
        Attachment a = get(me, id);
        boolean own = a.getUploader().getId().equals(me.id());
        permissions.check(me, a.getTicket().getSpace(), own ? Perm.ATTACH_DELETE_OWN : Perm.ATTACH_DELETE_ALL);
        String name = sanitize(filename);
        if (name.isBlank()) throw Errors.badRequest("EMPTY_NAME", "Nom de fichier obligatoire");
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (blockedExtensions.contains(ext)) throw Errors.badRequest("BLOCKED_TYPE", "Type de fichier interdit : ." + ext);
        if (name.equals(a.getFilename())) return a;
        history.save(new HistoryEntry(a.getTicket(), users.getReferenceById(me.id()), "attachment", a.getId().toString(), a.getId().toString(),
                a.getFilename(), name));
        a.setFilename(name);
        return a;
    }

    /** Best effort: no thumbnail for formats ImageIO cannot read (webp, svg…). */
    private String thumbnail(Path source, String key) {
        try {
            BufferedImage img = ImageIO.read(source.toFile());
            if (img == null) return null;
            int w = Math.min(THUMB_WIDTH, img.getWidth());
            int h = Math.max(1, img.getHeight() * w / img.getWidth());
            BufferedImage thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            var g = thumb.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, w, h, null);
            g.dispose();
            String thumbKey = key + ".thumb.png";
            Path out = root.resolve(thumbKey);
            ImageIO.write(thumb, "png", out.toFile());
            deleteOnRollback(out);
            return thumbKey;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void deleteOnRollback(Path path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) path.toFile().delete();
            }
        });
    }

    static String sanitize(String name) {
        String n = name == null ? "" : Path.of(name.replace('\\', '/')).getFileName().toString();
        n = n.replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (n.isEmpty()) n = "fichier";
        return n.length() > 255 ? n.substring(n.length() - 255) : n;
    }
}
