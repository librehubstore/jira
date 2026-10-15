package com.fluxo.notification;

import com.fluxo.ticket.HistoryEntry;
import com.fluxo.ticket.Ticket;
import com.fluxo.user.User;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Something happened on a ticket (spec §9). Published synchronously inside the business transaction,
 * so notifications are written or rolled back together with the change.
 */
public record TicketEvent(Type type, Ticket ticket, UUID actorId, List<Change> changes, String excerpt,
                          Set<User> mentioned, User previousAssignee) {

    public enum Type { CREATED, UPDATED, ASSIGNED, TRANSITIONED, RESOLVED, REOPENED, COMMENTED, COMMENT_EDITED, MENTIONED, DELETED, MOVED, BLOCKER_RESOLVED, COMMENT_DELETED }

    public record Change(String field, String from, String to) {
        public static List<Change> of(List<HistoryEntry> entries) {
            return entries.stream().map(h -> new Change(h.getField(), h.getOldDisplay(), h.getNewDisplay())).toList();
        }
    }

    public static TicketEvent of(Type type, Ticket ticket, UUID actorId) {
        return new TicketEvent(type, ticket, actorId, List.of(), null, Set.of(), null);
    }
}
