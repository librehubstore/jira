package com.fluxo.ticket;

import com.fluxo.space.Perm;
import com.fluxo.user.User;
import com.fluxo.workflow.Status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** JSON views of tickets, shared by the ticket, board and search APIs. */
public final class TicketViews {

    private TicketViews() {
    }

    public record UserRef(UUID id, String username, String displayName, boolean active, String avatar) {
        public static UserRef of(User u) {
            return u == null ? null : new UserRef(u.getId(), u.getUsername(), u.getDisplayName(), u.isActive(), u.avatarUrl());
        }
    }

    public record TypeRef(UUID id, String name, String icon, String color, int hierarchyLevel) {
        public static TypeRef of(TicketType t) {
            return new TypeRef(t.getId(), t.getName(), t.getIcon(), t.getColor(), t.getHierarchyLevel());
        }
    }

    public record StatusRef(UUID id, String name, String category) {
        public static StatusRef of(Status s) {
            return new StatusRef(s.getId(), s.getName(), s.getCategory().name());
        }
    }

    public record PriorityRef(UUID id, String name, String color, int rank) {
        public static PriorityRef of(RefData.Priority p) {
            return new PriorityRef(p.getId(), p.getName(), p.getColor(), p.getRank());
        }
    }

    public record NamedRef(UUID id, String name) {}

    /** Compact view for lists, children, links and board cards. */
    public record TicketRef(UUID id, String key, String summary, TypeRef type, StatusRef status, PriorityRef priority,
                            UserRef assignee) {
        public static TicketRef of(Ticket t) {
            return t == null ? null : new TicketRef(t.getId(), t.getKey(), t.getSummary(), TypeRef.of(t.getType()),
                    StatusRef.of(t.getStatus()), PriorityRef.of(t.getPriority()), UserRef.of(t.getAssignee()));
        }
    }

    public record Progress(int done, int total) {}

    public record TicketDetail(UUID id, String key, String spaceKey, String spaceName, int version,
                               TypeRef type, StatusRef status, NamedRef resolution, PriorityRef priority,
                               String summary, String description, UserRef assignee, UserRef reporter, UserRef creator,
                               TicketRef parent, LocalDate dueDate, LocalDate startDate, BigDecimal estimate,
                               List<String> labels, List<NamedRef> components, List<VersionRef> fixVersions,
                               Map<String, Object> customFields, List<UserRef> collaborators, boolean archived, boolean flagged, Instant resolvedAt, Instant createdAt,
                               Instant updatedAt, List<TicketRef> children, Progress progress, Set<Perm> myPermissions) {

        public static TicketDetail of(Ticket t, List<Ticket> children, Set<Perm> perms) {
            int done = (int) children.stream().filter(Ticket::isDone).count();
            return new TicketDetail(t.getId(), t.getKey(), t.getSpace().getKey(), t.getSpace().getName(), t.getVersion(),
                    TypeRef.of(t.getType()), StatusRef.of(t.getStatus()),
                    t.getResolution() == null ? null : new NamedRef(t.getResolution().getId(), t.getResolution().getName()),
                    PriorityRef.of(t.getPriority()), t.getSummary(), t.getDescription(),
                    UserRef.of(t.getAssignee()), UserRef.of(t.getReporter()), UserRef.of(t.getCreator()),
                    TicketRef.of(t.getParent()), t.getDueDate(), t.getStartDate(), t.getEstimate(),
                    t.getLabels().stream().map(Label::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                    t.getComponents().stream().map(c -> new NamedRef(c.getId(), c.getName())).sorted(Comparator.comparing(NamedRef::name, String.CASE_INSENSITIVE_ORDER)).toList(),
                    t.getFixVersions().stream().map(VersionRef::of).sorted(Comparator.comparing(VersionRef::name, String.CASE_INSENSITIVE_ORDER)).toList(),
                    t.getCustomValues(), t.getCollaborators().stream().map(UserRef::of).toList(), t.isArchived(), t.isFlagged(), t.getResolvedAt(), t.getCreatedAt(), t.getUpdatedAt(),
                    children.stream().map(TicketRef::of).toList(), new Progress(done, children.size()), perms);
        }
    }

    public record VersionRef(UUID id, String name, FixVersion.Status status) {
        public static VersionRef of(FixVersion v) {
            return new VersionRef(v.getId(), v.getName(), v.getStatus());
        }
    }

    public record HistoryView(UUID id, Instant at, UserRef actor, String field, String oldDisplay, String newDisplay) {
        public static HistoryView of(HistoryEntry h) {
            return new HistoryView(h.getId(), h.getAt(), UserRef.of(h.getActor()), h.getField(), h.getOldDisplay(), h.getNewDisplay());
        }
    }
}
