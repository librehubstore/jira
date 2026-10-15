package com.fluxo.collab;

import com.fluxo.ticket.Ticket;
import jakarta.persistence.*;

import java.util.UUID;

/** Ticket-to-ticket links and web links (spec §4.6). */
public final class Links {

    private Links() {
    }

    @Entity(name = "LinkType")
    @Table(name = "link_types")
    public static class LinkType {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        @Column(nullable = false, unique = true)
        private String name;
        @Column(nullable = false)
        private String outward;
        @Column(nullable = false)
        private String inward;

        protected LinkType() {
        }

        public UUID getId() { return id; }
        public String getName() { return name; }
        public String getOutward() { return outward; }
        public String getInward() { return inward; }
    }

    @Entity(name = "TicketLink")
    @Table(name = "ticket_links")
    public static class TicketLink {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        private Ticket source;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        private Ticket target;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        private LinkType linkType;

        protected TicketLink() {
        }

        public TicketLink(Ticket source, Ticket target, LinkType linkType) {
            this.source = source;
            this.target = target;
            this.linkType = linkType;
        }

        public UUID getId() { return id; }
        public Ticket getSource() { return source; }
        public Ticket getTarget() { return target; }
        public LinkType getLinkType() { return linkType; }
    }

    @Entity(name = "WebLink")
    @Table(name = "web_links")
    public static class WebLink {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        private Ticket ticket;
        @Column(nullable = false)
        private String url;
        private String title;

        protected WebLink() {
        }

        public WebLink(Ticket ticket, String url, String title) {
            this.ticket = ticket;
            this.url = url;
            this.title = title;
        }

        public UUID getId() { return id; }
        public Ticket getTicket() { return ticket; }
        public String getUrl() { return url; }
        public String getTitle() { return title; }
    }
}
