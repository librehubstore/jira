package com.fluxo.collab;

import com.fluxo.auth.AuthUser;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.ticket.Label;
import com.fluxo.ticket.Ticket;
import com.fluxo.ticket.TicketRepository;
import com.fluxo.ticket.TicketService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

/** Ticket cloning with options (spec §4.3): « COPIE – » summary, « clone » link, optional attachments, links, subtasks, comments. */
@Service
@Transactional
public class CloneService {

    public record Options(Boolean attachments, Boolean links, Boolean subtasks, Boolean comments) {}

    private final TicketService tickets;
    private final TicketRepository repo;
    private final PermissionService permissions;
    private final AttachmentService attachments;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    CloneService(TicketService tickets, TicketRepository repo, PermissionService permissions, AttachmentService attachments,
                 JdbcClient jdbc, JsonMapper json) {
        this.tickets = tickets;
        this.repo = repo;
        this.permissions = permissions;
        this.attachments = attachments;
        this.jdbc = jdbc;
        this.json = json;
    }

    public Ticket cloneTicket(AuthUser me, String key, Options o) {
        Ticket src = tickets.get(me, key);
        Ticket copy = copyOne(me, src, "COPIE – " + src.getSummary(), src.getParent() == null ? null : src.getParent().getKey(), o);
        jdbc.sql("""
                insert into ticket_links (id, source_id, target_id, link_type_id)
                select gen_random_uuid(), :copy, :src, id from link_types where name = 'Cloners'
                """).param("copy", copy.getId()).param("src", src.getId()).update();
        if (Boolean.TRUE.equals(o.links())) {
            permissions.check(me, src.getSpace(), Perm.LINK);
            jdbc.sql("""
                    insert into ticket_links (id, source_id, target_id, link_type_id)
                    select gen_random_uuid(), :copy, target_id, link_type_id from ticket_links where source_id = :src and target_id <> :copy
                    union all
                    select gen_random_uuid(), source_id, :copy, link_type_id from ticket_links where target_id = :src and source_id <> :copy
                    on conflict do nothing
                    """).param("copy", copy.getId()).param("src", src.getId()).update();
        }
        if (Boolean.TRUE.equals(o.subtasks())) {
            for (Ticket child : repo.findChildren(src.getId())) {
                if (child.getType().getHierarchyLevel() == -1) copyOne(me, child, child.getSummary(), copy.getKey(), o);
            }
        }
        return copy;
    }

    private Ticket copyOne(AuthUser me, Ticket src, String summary, String parentKey, Options o) {
        ObjectNode body = json.createObjectNode();
        body.put("typeId", src.getType().getId().toString());
        body.put("summary", summary.length() > 255 ? summary.substring(0, 255) : summary);
        if (src.getDescription() != null) body.put("description", src.getDescription()); // markdown: copied as is
        body.put("priorityId", src.getPriority().getId().toString());
        if (src.getAssignee() != null && permissions.isAssignable(src.getSpace(), src.getAssignee().getId())) {
            body.put("assigneeId", src.getAssignee().getId().toString());
        }
        if (src.getDueDate() != null) body.put("dueDate", src.getDueDate().toString());
        if (src.getStartDate() != null) body.put("startDate", src.getStartDate().toString());
        if (src.getEstimate() != null) body.put("estimate", src.getEstimate());
        body.set("labels", json.valueToTree(src.getLabels().stream().map(Label::getName).toList()));
        if (parentKey != null) body.put("parent", parentKey);
        if (!src.getCustomValues().isEmpty()) body.set("customFields", json.valueToTree(src.getCustomValues()));
        Ticket copy = tickets.create(me, src.getSpace(), body);
        repo.flush();
        if (Boolean.TRUE.equals(o.comments())) {
            jdbc.sql("""
                    insert into comments (id, ticket_id, author_id, body, edited_at, created_at)
                    select gen_random_uuid(), :copy, author_id, body, edited_at, created_at from comments where ticket_id = :src
                    """).param("copy", copy.getId()).param("src", src.getId()).update();
        }
        if (Boolean.TRUE.equals(o.attachments())) copyAttachments(src, copy);
        return copy;
    }

    private void copyAttachments(Ticket src, Ticket copy) {
        var rows = jdbc.sql("select uploader_id, filename, mime, size, storage_key, thumbnail_key from attachments where ticket_id = ?")
                .param(src.getId()).query().listOfRows();
        for (Map<String, Object> r : rows) {
            String key = UUID.randomUUID().toString();
            String thumb = r.get("thumbnail_key") == null ? null : key + ".thumb.png";
            try {
                // ponytail: copied files are not removed if the transaction rolls back afterwards (orphans, no data leak).
                Files.copy(attachments.file((String) r.get("storage_key")), attachments.file(key));
                if (thumb != null) Files.copy(attachments.file((String) r.get("thumbnail_key")), attachments.file(thumb));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            jdbc.sql("""
                    insert into attachments (id, ticket_id, uploader_id, filename, mime, size, storage_key, thumbnail_key)
                    values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?)
                    """).params(copy.getId(), r.get("uploader_id"), r.get("filename"), r.get("mime"), r.get("size"), key, thumb).update();
        }
    }
}
