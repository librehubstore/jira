package com.fluxo.board;

import com.fluxo.auth.AuthUser;
import com.fluxo.collab.TicketLinkRepository;
import com.fluxo.common.Errors;
import com.fluxo.realtime.RealtimeService;
import com.fluxo.search.SearchService;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.space.Space;
import com.fluxo.space.SpaceRepository;
import com.fluxo.ticket.Label;
import com.fluxo.ticket.Rank;
import com.fluxo.ticket.Ticket;
import com.fluxo.ticket.TicketRepository;
import com.fluxo.ticket.TicketService;
import com.fluxo.ticket.TicketViews.*;
import com.fluxo.workflow.Status;
import com.fluxo.workflow.StatusCategory;
import com.fluxo.workflow.Workflow;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Kanban board: configuration, cards and moves (spec §4.10). */
@Service
@Transactional
public class BoardService {

    static final Set<String> CARD_FIELDS = Set.of("dueDate", "labels", "parent", "startDate", "estimate", "daysInColumn", "components", "fixVersions");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    private final BoardRepository boards;
    private final QuickFilterRepository quickFilters;
    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final TicketLinkRepository links;
    private final PermissionService permissions;
    private final SpaceRepository spaces;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final SearchService search;
    private final ApplicationEventPublisher events;

    BoardService(BoardRepository boards, QuickFilterRepository quickFilters, TicketRepository tickets,
                 TicketService ticketService, TicketLinkRepository links, PermissionService permissions,
                 SpaceRepository spaces, JdbcClient jdbc, Clock clock, SearchService search,
                 ApplicationEventPublisher events) {
        this.boards = boards;
        this.quickFilters = quickFilters;
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.links = links;
        this.permissions = permissions;
        this.spaces = spaces;
        this.jdbc = jdbc;
        this.clock = clock;
        this.search = search;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Board get(AuthUser me, UUID boardId) {
        Board b = boards.findById(boardId).orElseThrow(() -> Errors.notFound("Board introuvable"));
        if (!permissions.has(me, b.getSpace(), Perm.BROWSE)) throw Errors.notFound("Board introuvable");
        return b;
    }

    /** Spaces of the board this user can browse: their tickets are the board's tickets. */
    List<Space> visibleSpaces(AuthUser me, Board b) {
        return b.allSpaces().stream().filter(s -> permissions.has(me, s, Perm.BROWSE)).toList();
    }

    private static List<UUID> ids(List<Space> spaces) {
        return spaces.stream().map(Space::getId).toList();
    }

    // --- Cards ------------------------------------------------------------------

    public record Card(UUID id, String key, String summary, int version, TypeRef type, UUID statusId, PriorityRef priority,
                       UserRef assignee, UserRef reporter, TicketRef parent, List<String> labels, String rank, boolean flagged,
                       LocalDate dueDate, LocalDate startDate, BigDecimal estimate, Instant updatedAt,
                       Progress subtasks, int comments, int attachments, boolean blocked,
                       /** Index of the first matching swimlane query (QUERIES mode), -1 for « Le reste ». */
                       Integer lane,
                       /** Colour from the first matching colour query (QUERIES colour mode), else null. */
                       String color,
                       /** When the ticket entered its current status (« jours dans la colonne »). */
                       Instant statusSince, List<String> components, List<String> fixVersions, List<UserRef> collaborators) {}

    /** {@code labelColors}: lower-cased label name → colour, for the coloured labels of the cards (spec §4.13). */
    public record BoardCards(List<Card> cards, long unmappedCount, Map<String, String> labelColors) {}

    /**
     * Cards of the board. With the Kanban backlog on, the first column (« Backlog ») is left out of the board
     * and {@code backlog = true} returns only the first two columns (« Backlog » and « Sélectionné »).
     */
    @Transactional(readOnly = true)
    public BoardCards cards(AuthUser me, UUID boardId, boolean backlog) {
        Board b = get(me, boardId);
        List<UUID> spaceIds = ids(visibleSpaces(me, b));
        Set<UUID> mapped = mappedStatusIds(b);
        if (mapped.isEmpty()) return new BoardCards(List.of(), tickets.countUnmapped(spaceIds, Set.of(new UUID(0, 0))), Map.of());
        Set<UUID> shown = shownStatusIds(b, backlog);
        Instant doneSince = b.getHideDoneDays() == null || backlog ? Instant.EPOCH : clock.instant().minus(Duration.ofDays(b.getHideDoneDays()));
        List<Ticket> list = shown.isEmpty() ? List.of() : tickets.findForBoard(spaceIds, shown, doneSince);
        if (!b.isShowSubtasks()) list = list.stream().filter(t -> t.getType().getHierarchyLevel() != -1).toList();
        // Tickets of a released (or archived) version leave the board (spec §4.10 « Publier une version »).
        Set<UUID> released = new HashSet<>(jdbc.sql("""
                select tv.ticket_id from ticket_fix_versions tv join versions v on v.id = tv.version_id
                join tickets t on t.id = tv.ticket_id where t.space_id in (:spaces) and v.status <> 'UNRELEASED'
                """).param("spaces", spaceIds).query(UUID.class).list());
        if (!released.isEmpty()) list = list.stream().filter(t -> !released.contains(t.getId())).toList();
        if (b.getBaseFql() != null && !b.getBaseFql().isBlank()) {
            Set<UUID> kept = search.matching(me, b.getBaseFql(), list.stream().map(Ticket::getId).toList());
            list = list.stream().filter(t -> kept.contains(t.getId())).toList();
        }
        List<UUID> listIds = list.stream().map(Ticket::getId).toList();
        Map<UUID, Integer> lanes = b.getSwimlaneMode() == Board.SwimlaneMode.QUERIES
                ? firstMatch(me, b.getSwimlaneQueries().stream().map(Board.LaneQuery::fql).toList(), listIds) : Map.of();
        Map<UUID, Integer> colors = b.getCardColorMode() == Board.ColorMode.QUERIES
                ? firstMatch(me, b.getCardColorQueries().stream().map(Board.ColorQuery::fql).toList(), listIds) : Map.of();

        Map<String, Object> p = Map.of("spaces", spaceIds);
        Map<UUID, Integer> comments = counts("select c.ticket_id as id, count(*) as n from comments c join tickets t on t.id = c.ticket_id where t.space_id in (:spaces) group by c.ticket_id", p);
        Map<UUID, Integer> attachments = counts("select a.ticket_id as id, count(*) as n from attachments a join tickets t on t.id = a.ticket_id where t.space_id in (:spaces) group by a.ticket_id", p);
        Map<UUID, Progress> progress = new HashMap<>();
        jdbc.sql("""
                        select t.parent_id as id, count(*) as total, count(*) filter (where s.category = 'DONE') as done
                        from tickets t join statuses s on s.id = t.status_id join ticket_types ty on ty.id = t.type_id
                        where t.space_id in (:spaces) and t.parent_id is not null and ty.hierarchy_level = -1 group by t.parent_id
                        """).params(p)
                .query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), new Progress(rs.getInt("done"), rs.getInt("total"))))
                .list().forEach(e -> progress.put(e.getKey(), e.getValue()));
        Map<UUID, Instant> since = new HashMap<>();
        jdbc.sql("""
                        select h.ticket_id as id, max(h.at) as at from history_entries h join tickets t on t.id = h.ticket_id
                        where t.space_id in (:spaces) and h.field = 'status' group by h.ticket_id
                        """).params(p)
                .query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getTimestamp("at").toInstant()))
                .list().forEach(e -> since.put(e.getKey(), e.getValue()));
        Set<UUID> blocked = new HashSet<>(links.blockedAmong(listIds));
        Map<UUID, List<String>> componentNames = names("""
                select tc.ticket_id as id, c.name from ticket_components tc join components c on c.id = tc.component_id
                join tickets t on t.id = tc.ticket_id where t.space_id in (:spaces) order by lower(c.name)""", p);
        Map<UUID, List<String>> versionNames = names("""
                select tv.ticket_id as id, v.name from ticket_fix_versions tv join versions v on v.id = tv.version_id
                join tickets t on t.id = tv.ticket_id where t.space_id in (:spaces) order by lower(v.name)""", p);

        List<Card> cards = list.stream().map(t -> new Card(t.getId(), t.getKey(), t.getSummary(), t.getVersion(),
                TypeRef.of(t.getType()), t.getStatus().getId(), PriorityRef.of(t.getPriority()), UserRef.of(t.getAssignee()),
                UserRef.of(t.getReporter()), t.getParent() == null ? null : new TicketRef(t.getParent().getId(), t.getParent().getKey(), t.getParent().getSummary(),
                        TypeRef.of(t.getParent().getType()), StatusRef.of(t.getParent().getStatus()), PriorityRef.of(t.getParent().getPriority()), null),
                t.getLabels().stream().map(Label::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                t.getRank(), t.isFlagged(), t.getDueDate(), t.getStartDate(), t.getEstimate(), t.getUpdatedAt(),
                progress.get(t.getId()), comments.getOrDefault(t.getId(), 0), attachments.getOrDefault(t.getId(), 0),
                blocked.contains(t.getId()), lanes.getOrDefault(t.getId(), -1),
                colors.containsKey(t.getId()) ? b.getCardColorQueries().get(colors.get(t.getId())).color() : null,
                since.getOrDefault(t.getId(), t.getCreatedAt()),
                componentNames.getOrDefault(t.getId(), List.of()), versionNames.getOrDefault(t.getId(), List.of()),
                t.getCollaborators().stream().map(UserRef::of).toList())).toList();
        Map<String, String> labelColors = new HashMap<>();
        jdbc.sql("select lower(name) as name, color from labels where space_id in (:spaces) and color is not null").params(p)
                .query((rs, i) -> Map.entry(rs.getString("name"), rs.getString("color"))).list().forEach(e -> labelColors.put(e.getKey(), e.getValue()));
        return new BoardCards(cards, tickets.countUnmapped(spaceIds, mapped), labelColors);
    }

    private Map<UUID, List<String>> names(String sql, Map<String, Object> params) {
        Map<UUID, List<String>> m = new HashMap<>();
        jdbc.sql(sql).params(params).query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("name")))
                .list().forEach(e -> m.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return m;
    }

    /**
     * « Publier une version » (spec §4.10): the chosen (or new) version goes on every ticket of the done column, then is
     * released, which takes those tickets off the board. Returns how many tickets were released.
     */
    public int release(AuthUser me, UUID boardId, UUID versionId, String newName) {
        Board b = get(me, boardId);
        permissions.check(me, b.getSpace(), Perm.MANAGE_VERSIONS);
        BoardColumn done = b.getColumns().getLast();
        UUID version = versionId;
        if (version == null) {
            if (newName == null || newName.isBlank()) throw Errors.badRequest("NAME_REQUIRED", "Choisissez ou nommez une version");
            if (jdbc.sql("select count(*) from versions where space_id = ? and lower(name) = lower(?)").params(b.getSpace().getId(), newName.strip())
                    .query(Long.class).optional().orElse(0L) > 0) throw Errors.conflict("NAME_TAKEN", "« " + newName.strip() + " » existe déjà dans cet espace");
            version = UUID.randomUUID();
            jdbc.sql("insert into versions (id, space_id, name) values (?, ?, ?)").params(version, b.getSpace().getId(), newName.strip()).update();
        } else if (jdbc.sql("select count(*) from versions where id = ? and space_id = ? and status = 'UNRELEASED'").params(version, b.getSpace().getId())
                .query(Long.class).optional().orElse(0L) == 0) {
            throw Errors.badRequest("INVALID_VERSION", "Version non publiée de l'espace attendue");
        }
        int n = jdbc.sql("""
                insert into ticket_fix_versions (ticket_id, version_id)
                select t.id, :version from tickets t where t.space_id = :space and t.status_id in (:statuses)
                  and not exists (select 1 from ticket_fix_versions tv join versions v on v.id = tv.version_id
                                  where tv.ticket_id = t.id and v.status <> 'UNRELEASED')
                on conflict do nothing
                """).param("version", version).param("space", b.getSpace().getId())
                .param("statuses", done.getStatuses().stream().map(Status::getId).toList()).update();
        jdbc.sql("update versions set status = 'RELEASED', release_date = coalesce(release_date, ?) where id = ?")
                .params(java.sql.Date.valueOf(LocalDate.now(clock)), version).update();
        return n;
    }

    /** Ticket id → index of the first query it matches (swimlanes and card colours by query). */
    private Map<UUID, Integer> firstMatch(AuthUser me, List<String> queries, Collection<UUID> candidates) {
        Map<UUID, Integer> result = new HashMap<>();
        Set<UUID> remaining = new HashSet<>(candidates);
        for (int i = 0; i < queries.size() && !remaining.isEmpty(); i++) {
            for (UUID id : search.matching(me, queries.get(i), remaining)) result.put(id, i);
            remaining.removeAll(result.keySet());
        }
        return result;
    }

    private Map<UUID, Integer> counts(String sql, Map<String, Object> params) {
        Map<UUID, Integer> m = new HashMap<>();
        jdbc.sql(sql).params(params).query((rs, i) -> Map.entry(rs.getObject("id", UUID.class), rs.getInt("n")))
                .list().forEach(e -> m.put(e.getKey(), e.getValue()));
        return m;
    }

    /** Board tickets matching an FQL query (quick filters written in FQL are evaluated here). */
    @Transactional(readOnly = true)
    public Set<UUID> match(AuthUser me, UUID boardId, String fql) {
        Board b = get(me, boardId);
        List<UUID> candidates = jdbc.sql("select id from tickets where space_id in (:spaces)")
                .param("spaces", ids(visibleSpaces(me, b))).query(UUID.class).list();
        return search.matching(me, fql, candidates);
    }

    // --- Move ---------------------------------------------------------------------

    /** Dragging a card to another swimlane updates the field of that swimlane (spec §4.10). */
    public record LaneChange(String field, String value) {}

    /**
     * Drag and drop: optional transition to {@code statusId}, optional swimlane change, then rank between the cards
     * shown above and below. A refused change throws before anything is saved, so the card goes back to its place.
     */
    public Ticket move(AuthUser me, UUID boardId, String ticketKey, UUID statusId, String aboveKey, String belowKey,
                       Integer version, LaneChange lane, UUID resolutionId, String comment) {
        Board b = get(me, boardId);
        Ticket t = ticketService.get(me, ticketKey);
        if (!ids(visibleSpaces(me, b)).contains(t.getSpace().getId())) throw Errors.badRequest("NOT_ON_BOARD", "Ce ticket n'est pas sur ce board");
        if (version != null && version != t.getVersion()) {
            throw Errors.conflict("TICKET_CONFLICT", "Ce ticket a été modifié entre-temps par quelqu'un d'autre");
        }
        if (statusId != null && !statusId.equals(t.getStatus().getId())) {
            BoardColumn col = b.getColumns().stream().filter(c -> c.getStatuses().stream().anyMatch(s -> s.getId().equals(statusId)))
                    .findFirst().orElseThrow(() -> Errors.badRequest("INVALID_STATUS", "Statut absent du board"));
            // Multi-space board: same column, but the status of the ticket's own workflow.
            UUID wf = t.getSpace().workflowFor(t.getType()).getId();
            Status target = col.getStatuses().stream().filter(s -> s.getId().equals(statusId) && s.getWorkflow().getId().equals(wf)).findFirst()
                    .or(() -> col.getStatuses().stream().filter(s -> s.getWorkflow().getId().equals(wf)).findFirst())
                    .orElseThrow(() -> Errors.badRequest("INVALID_STATUS", "Aucun statut de « " + col.getName() + " » dans le workflow de " + t.getKey()));
            BoardColumn from = b.getColumns().stream().filter(c -> c.getStatuses().contains(t.getStatus())).findFirst().orElse(null);
            if (b.isWipBlocking() && col != from && col.getWipMax() != null) {
                long count = wipCount(me, b, col);
                if (count >= col.getWipMax()) {
                    throw Errors.conflict("WIP_LIMIT", "Limite WIP atteinte dans « " + col.getName() + " » (" + count + "/" + col.getWipMax() + ")");
                }
            }
            ticketService.transition(me, ticketKey, target.getId(), resolutionId, null, comment);
        }
        if (lane != null) applyLane(me, t, lane);
        if (aboveKey != null || belowKey != null) {
            permissions.check(me, t.getSpace(), Perm.EDIT);
            String above = rankOf(me, aboveKey), below = rankOf(me, belowKey);
            if (above != null && below != null && above.compareTo(below) >= 0) below = null; // stale view: just go after "above"
            String rank = Rank.between(above, below);
            if (!rank.equals(t.getRank())) rerank(me, t, rank);
        }
        return t;
    }

    private void applyLane(AuthUser me, Ticket t, LaneChange lane) {
        String field = switch (lane.field() == null ? "" : lane.field()) {
            case "ASSIGNEE" -> "assigneeId";
            case "PRIORITY" -> "priorityId";
            case "PARENT" -> "parent";
            default -> throw Errors.badRequest("INVALID_LANE", "Ce mode de couloir ne modifie pas de champ");
        };
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        if (lane.value() == null || lane.value().isBlank()) body.putNull(field);
        else body.put(field, lane.value());
        ticketService.update(me, t.getKey(), body);
    }

    /** Tickets of a column counted like the board header does (quick filters ignored). */
    private long wipCount(AuthUser me, Board b, BoardColumn col) {
        Instant doneSince = b.getHideDoneDays() == null ? Instant.EPOCH : clock.instant().minus(Duration.ofDays(b.getHideDoneDays()));
        return jdbc.sql("""
                        select count(*) from tickets t join ticket_types ty on ty.id = t.type_id
                        where t.space_id in (:spaces) and t.status_id in (:statuses)
                          and (t.resolved_at is null or t.resolved_at >= :since) and (:all or ty.hierarchy_level <> -1)
                        """)
                .param("spaces", ids(visibleSpaces(me, b)))
                .param("statuses", col.getStatuses().stream().map(Status::getId).toList())
                .param("since", java.sql.Timestamp.from(doneSince))
                .param("all", b.getWipMode() != Board.WipMode.COUNT_EXCL_SUBTASKS)
                .query(Long.class).optional().orElse(0L);
    }

    /** Moves to the top (or bottom) of all tickets of the board's spaces. */
    public Ticket sendTo(AuthUser me, UUID boardId, String ticketKey, boolean top) {
        Board b = get(me, boardId);
        Ticket t = ticketService.get(me, ticketKey);
        permissions.check(me, t.getSpace(), Perm.EDIT);
        List<UUID> spaceIds = ids(visibleSpaces(me, b));
        String min = jdbc.sql("select min(rank) from tickets where space_id in (:s)").param("s", spaceIds).query(String.class).single();
        String max = jdbc.sql("select max(rank) from tickets where space_id in (:s)").param("s", spaceIds).query(String.class).single();
        if (top && !t.getRank().equals(min)) rerank(me, t, Rank.between(null, min));
        if (!top && !t.getRank().equals(max)) rerank(me, t, Rank.between(max, null));
        return t;
    }

    private void rerank(AuthUser me, Ticket t, String rank) {
        t.setRank(rank);
        events.publishEvent(new RealtimeService.Changed(t.getSpace().getId(), t.getSpace().getKey(), t.getKey(), me.id()));
    }

    private String rankOf(AuthUser me, String key) {
        if (key == null) return null;
        int dash = key.lastIndexOf('-');
        if (dash < 0) return null;
        try {
            Space s = spaces.findByKey(key.substring(0, dash)).orElse(null);
            return s == null ? null : tickets.rankOf(s.getId(), Integer.parseInt(key.substring(dash + 1))).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- Boards of a space ------------------------------------------------------

    /** New board of the space: one column per status of the workflow. */
    public Board create(AuthUser me, String spaceKey, String name) {
        Space space = spaces.findByKey(spaceKey).filter(s -> permissions.has(me, s, Perm.BROWSE))
                .orElseThrow(() -> Errors.notFound("Espace introuvable"));
        permissions.check(me, space, Perm.MANAGE_BOARD);
        if (name == null || name.isBlank()) throw Errors.badRequest("BOARD_NAME", "Le board a un nom");
        Board b = new Board(space, name.strip(), boards.findBySpaceIdOrderByNameAsc(space.getId()).isEmpty());
        for (Status s : space.getWorkflow().getStatuses()) b.addColumn(s.getName()).getStatuses().add(s);
        // ponytail: per-type workflow statuses are added to the column of the same name, others stay unmapped.
        space.allWorkflows().stream().skip(1).flatMap(w -> w.getStatuses().stream()).forEach(s -> b.getColumns().stream()
                .filter(c -> c.getName().equalsIgnoreCase(s.getName())).findFirst().ifPresent(c -> c.getStatuses().add(s)));
        return boards.save(b);
    }

    public void delete(AuthUser me, UUID boardId) {
        Board b = manageable(me, boardId);
        if (b.isDefault()) throw Errors.conflict("DEFAULT_BOARD", "Choisissez d'abord un autre board par défaut");
        quickFilters.deleteByBoard(b.getId());
        boards.delete(b);
    }

    /** The board opened from the space navigation (spec §4.10). */
    public Board makeDefault(AuthUser me, UUID boardId) {
        Board b = manageable(me, boardId);
        boards.findBySpaceIdOrderByNameAsc(b.getSpace().getId()).forEach(o -> o.setDefault(o == b));
        return b;
    }

    // --- Configuration ----------------------------------------------------------

    public record ColumnSpec(UUID id, String name, Integer wipMin, Integer wipMax, List<UUID> statusIds, String newStatusName) {}

    /** Optional settings of a board; null = unchanged. */
    public record Settings(String name, Integer hideDoneDays, Boolean neverHide, Board.SwimlaneMode swimlaneMode,
                           Board.WipMode wipMode, List<String> cardFields, Boolean showSubtasks, String baseFql,
                           List<Board.LaneQuery> swimlaneQueries, List<String> otherSpaceKeys, List<Integer> workingDays,
                           Boolean backlogEnabled, Board.ColorMode cardColorMode, List<Board.ColorQuery> cardColorQueries,
                           Boolean wipBlocking) {}

    public Board update(AuthUser me, UUID boardId, Settings req) {
        Board b = manageable(me, boardId);
        if (req.name() != null && !req.name().isBlank()) b.setName(req.name().strip());
        if (Boolean.TRUE.equals(req.neverHide())) b.setHideDoneDays(null);
        else if (req.hideDoneDays() != null) {
            if (!Set.of(1, 7, 14, 28).contains(req.hideDoneDays())) throw Errors.badRequest("INVALID_HIDE_DAYS", "Choisir 1, 7, 14 ou 28 jours");
            b.setHideDoneDays(req.hideDoneDays());
        }
        if (req.swimlaneMode() != null) b.setSwimlaneMode(req.swimlaneMode());
        if (req.wipMode() != null) b.setWipMode(req.wipMode());
        if (req.cardFields() != null) {
            if (req.cardFields().size() > 3 || !CARD_FIELDS.containsAll(req.cardFields())) {
                throw Errors.badRequest("INVALID_CARD_FIELDS", "Jusqu'à 3 champs parmi " + CARD_FIELDS);
            }
            b.setCardFields(new ArrayList<>(new LinkedHashSet<>(req.cardFields())));
        }
        if (req.showSubtasks() != null) b.setShowSubtasks(req.showSubtasks());
        if (req.baseFql() != null) {
            search.parse(req.baseFql());
            b.setBaseFql(req.baseFql().isBlank() ? null : req.baseFql().strip());
        }
        if (req.swimlaneQueries() != null) {
            for (Board.LaneQuery q : req.swimlaneQueries()) {
                if (q.name() == null || q.name().isBlank()) throw Errors.badRequest("LANE_NAME", "Chaque couloir a un nom");
                search.parse(q.fql());
            }
            b.setSwimlaneQueries(new ArrayList<>(req.swimlaneQueries()));
        }
        if (req.otherSpaceKeys() != null) {
            Set<Space> others = new HashSet<>();
            for (String key : req.otherSpaceKeys()) {
                Space s = spaces.findByKey(key).filter(x -> permissions.has(me, x, Perm.BROWSE))
                        .orElseThrow(() -> Errors.badRequest("UNKNOWN_SPACE", "Espace inconnu : " + key));
                if (!s.equals(b.getSpace())) others.add(s);
            }
            b.getExtraSpaces().clear();
            b.getExtraSpaces().addAll(others);
        }
        if (req.workingDays() != null) {
            if (req.workingDays().isEmpty() || req.workingDays().stream().anyMatch(d -> d == null || d < 1 || d > 7)) {
                throw Errors.badRequest("INVALID_WORKING_DAYS", "Jours ouvrés : au moins un jour entre 1 (lundi) et 7 (dimanche)");
            }
            b.setWorkingDays(new ArrayList<>(new TreeSet<>(req.workingDays())));
        }
        if (req.backlogEnabled() != null) {
            if (req.backlogEnabled() && b.getColumns().size() < 3) {
                throw Errors.badRequest("BACKLOG_COLUMNS", "Le backlog utilise les deux premières colonnes : il en faut au moins trois");
            }
            b.setBacklogEnabled(req.backlogEnabled());
        }
        if (req.cardColorMode() != null) b.setCardColorMode(req.cardColorMode());
        if (req.cardColorQueries() != null) {
            for (Board.ColorQuery q : req.cardColorQueries()) {
                if (q.color() == null || !COLOR.matcher(q.color()).matches()) throw Errors.badRequest("INVALID_COLOR", "Couleur attendue au format #rrggbb");
                search.parse(q.fql());
            }
            b.setCardColorQueries(new ArrayList<>(req.cardColorQueries()));
        }
        if (req.wipBlocking() != null) b.setWipBlocking(req.wipBlocking());
        return b;
    }

    /** Replaces the column layout. A column may create a new status in the simplified workflow (spec §4.10). */
    public Board setColumns(AuthUser me, UUID boardId, List<ColumnSpec> specs) {
        Board b = manageable(me, boardId);
        if (specs.isEmpty()) throw Errors.badRequest("NO_COLUMNS", "Un board a au moins une colonne");
        Workflow wf = b.getSpace().getWorkflow();
        Map<UUID, Status> byId = new HashMap<>();
        b.allSpaces().forEach(s -> s.allWorkflows().forEach(w -> w.getStatuses().forEach(st -> byId.put(st.getId(), st))));
        Set<UUID> seen = new HashSet<>();
        Map<UUID, BoardColumn> existing = new HashMap<>();
        b.getColumns().forEach(c -> existing.put(c.getId(), c));

        List<BoardColumn> result = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            ColumnSpec spec = specs.get(i);
            if (spec.name() == null || spec.name().isBlank()) throw Errors.badRequest("COLUMN_NAME", "Chaque colonne a un nom");
            if (spec.wipMin() != null && spec.wipMax() != null && spec.wipMin() > spec.wipMax()) {
                throw Errors.badRequest("INVALID_WIP", "Minimum supérieur au maximum dans « " + spec.name() + " »");
            }
            BoardColumn c = spec.id() != null && existing.containsKey(spec.id()) ? existing.remove(spec.id()) : b.addColumn(spec.name());
            c.setName(spec.name().strip());
            c.setPosition(i);
            c.setWipMin(positiveOrNull(spec.wipMin()));
            c.setWipMax(positiveOrNull(spec.wipMax()));
            c.getStatuses().clear();
            for (UUID sid : spec.statusIds() == null ? List.<UUID>of() : spec.statusIds()) {
                Status s = byId.get(sid);
                if (s == null) throw Errors.badRequest("INVALID_STATUS", "Statut inconnu dans ce workflow");
                if (!seen.add(sid)) throw Errors.badRequest("STATUS_TWICE", "Le statut « " + s.getName() + " » est dans deux colonnes");
                c.getStatuses().add(s);
            }
            if (spec.newStatusName() != null && !spec.newStatusName().isBlank()) {
                if (wf.isShared()) throw Errors.badRequest("SHARED_WORKFLOW", "Workflow partagé : créez le statut côté administration");
                String statusName = spec.newStatusName().strip();
                if (wf.getStatuses().stream().anyMatch(s -> s.getName().equalsIgnoreCase(statusName))) {
                    throw Errors.conflict("STATUS_EXISTS", "Le statut « " + statusName + " » existe déjà");
                }
                boolean last = i == specs.size() - 1;
                Status s = wf.addStatus(statusName, last ? StatusCategory.DONE : i == 0 ? StatusCategory.TODO : StatusCategory.IN_PROGRESS);
                c.getStatuses().add(s);
            }
            result.add(c);
        }
        b.getColumns().removeIf(c -> !result.contains(c)); // removed columns: their statuses become unmapped
        b.getColumns().sort(Comparator.comparingInt(BoardColumn::getPosition));
        if (b.isBacklogEnabled() && b.getColumns().size() < 3) b.setBacklogEnabled(false);
        boards.flush();
        return b;
    }

    public record FilterSpec(String name, Map<String, Object> criteria) {}

    public List<QuickFilter> setQuickFilters(AuthUser me, UUID boardId, List<FilterSpec> specs) {
        Board b = manageable(me, boardId);
        quickFilters.deleteByBoard(b.getId());
        quickFilters.flush();
        List<QuickFilter> result = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            FilterSpec f = specs.get(i);
            if (f.name() == null || f.name().isBlank()) throw Errors.badRequest("FILTER_NAME", "Chaque filtre a un nom");
            if (f.criteria() != null && f.criteria().get("fql") instanceof String fql) search.parse(fql);
            result.add(quickFilters.save(new QuickFilter(b, f.name().strip(), f.criteria() == null ? Map.of() : f.criteria(), i)));
        }
        return result;
    }

    private Board manageable(AuthUser me, UUID boardId) {
        Board b = get(me, boardId);
        permissions.check(me, b.getSpace(), Perm.MANAGE_BOARD);
        return b;
    }

    static Set<UUID> mappedStatusIds(Board b) {
        Set<UUID> ids = new HashSet<>();
        b.getColumns().forEach(c -> c.getStatuses().forEach(s -> ids.add(s.getId())));
        return ids;
    }

    /** Statuses whose tickets are shown: the board without the backlog column, or the backlog's two columns. */
    static Set<UUID> shownStatusIds(Board b, boolean backlog) {
        if (!b.isBacklogEnabled()) return backlog ? Set.of() : mappedStatusIds(b);
        List<BoardColumn> cols = b.getColumns();
        Set<UUID> ids = new HashSet<>();
        (backlog ? cols.subList(0, 2) : cols.subList(1, cols.size())).forEach(c -> c.getStatuses().forEach(s -> ids.add(s.getId())));
        return ids;
    }

    private static Integer positiveOrNull(Integer v) {
        return v == null || v <= 0 ? null : v;
    }
}
