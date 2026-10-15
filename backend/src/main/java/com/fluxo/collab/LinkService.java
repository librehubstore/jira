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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class LinkService {

    private final TicketLinkRepository links;
    private final LinkTypeRepository linkTypes;
    private final WebLinkRepository webLinks;
    private final TicketService tickets;
    private final PermissionService permissions;
    private final HistoryRepository history;
    private final UserRepository users;

    LinkService(TicketLinkRepository links, LinkTypeRepository linkTypes, WebLinkRepository webLinks,
                TicketService tickets, PermissionService permissions, HistoryRepository history, UserRepository users) {
        this.links = links;
        this.linkTypes = linkTypes;
        this.webLinks = webLinks;
        this.tickets = tickets;
        this.permissions = permissions;
        this.history = history;
        this.users = users;
    }

    /** "outward": ticket → other (e.g. A blocks B); "inward": other → ticket (A is blocked by B). */
    public Links.TicketLink link(AuthUser me, String ticketKey, UUID linkTypeId, String otherKey, boolean inward) {
        Ticket ticket = tickets.get(me, ticketKey);
        permissions.check(me, ticket.getSpace(), Perm.LINK);
        Ticket other = tickets.get(me, otherKey);
        if (other.getId().equals(ticket.getId())) throw Errors.badRequest("SELF_LINK", "Un ticket ne peut pas être lié à lui-même");
        Links.LinkType type = linkTypes.findById(linkTypeId).orElseThrow(() -> Errors.badRequest("INVALID_LINK_TYPE", "Type de lien inconnu"));
        Ticket source = inward ? other : ticket, target = inward ? ticket : other;
        if (links.existsBySourceIdAndTargetIdAndLinkTypeId(source.getId(), target.getId(), type.getId())) {
            throw Errors.conflict("LINK_EXISTS", "Ce lien existe déjà");
        }
        Links.TicketLink l = links.save(new Links.TicketLink(source, target, type));
        record(me, source, null, type.getOutward() + " " + target.getKey());
        record(me, target, null, type.getInward() + " " + source.getKey());
        return l;
    }

    public void unlink(AuthUser me, UUID linkId) {
        Links.TicketLink l = links.findById(linkId).orElseThrow(() -> Errors.notFound("Lien introuvable"));
        boolean allowed = permissions.has(me, l.getSource().getSpace(), Perm.LINK) || permissions.has(me, l.getTarget().getSpace(), Perm.LINK);
        if (!allowed) throw Errors.forbidden("Vous ne pouvez pas modifier les liens de ces tickets");
        record(me, l.getSource(), l.getLinkType().getOutward() + " " + l.getTarget().getKey(), null);
        record(me, l.getTarget(), l.getLinkType().getInward() + " " + l.getSource().getKey(), null);
        links.delete(l);
    }

    @Transactional(readOnly = true)
    public List<Links.TicketLink> list(AuthUser me, String ticketKey) {
        return links.findForTicket(tickets.get(me, ticketKey).getId());
    }

    public Links.WebLink addWebLink(AuthUser me, String ticketKey, String url, String title) {
        Ticket t = tickets.get(me, ticketKey);
        permissions.check(me, t.getSpace(), Perm.LINK);
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw Errors.badRequest("INVALID_URL", "URL invalide");
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()) || uri.getHost() == null) {
            throw Errors.badRequest("INVALID_URL", "Seules les URL http(s) sont acceptées");
        }
        String label = title == null || title.isBlank() ? uri.getHost() : title.strip();
        Links.WebLink w = webLinks.save(new Links.WebLink(t, uri.toString(), label.length() > 255 ? label.substring(0, 255) : label));
        record(me, t, null, "lien web " + label);
        return w;
    }

    public void deleteWebLink(AuthUser me, UUID id) {
        Links.WebLink w = webLinks.findById(id).orElseThrow(() -> Errors.notFound("Lien introuvable"));
        permissions.check(me, w.getTicket().getSpace(), Perm.LINK);
        record(me, w.getTicket(), "lien web " + w.getTitle(), null);
        webLinks.delete(w);
    }

    @Transactional(readOnly = true)
    public List<Links.WebLink> webLinks(AuthUser me, String ticketKey) {
        return webLinks.findByTicketIdOrderByTitleAsc(tickets.get(me, ticketKey).getId());
    }

    private void record(AuthUser me, Ticket t, String oldDisplay, String newDisplay) {
        history.save(new HistoryEntry(t, users.getReferenceById(me.id()), "link", null, null, oldDisplay, newDisplay));
    }
}
