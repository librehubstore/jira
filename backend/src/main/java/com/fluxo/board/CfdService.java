package com.fluxo.board;

import com.fluxo.auth.AuthUser;
import com.fluxo.common.Errors;
import com.fluxo.search.SearchService;
import com.fluxo.space.Space;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/**
 * Cumulative flow diagram (spec §4.15): for each day, how many tickets have reached each column or a column further
 * right, rebuilt from the status history. Optionally narrowed by an FQL query (quick filter, swimlane query).
 */
@Service
@Transactional(readOnly = true)
public class CfdService {

    public record Day(LocalDate date, List<Integer> cumulative) {}

    public record Cfd(List<String> columns, List<Day> days) {}

    private final BoardService boards;
    private final SearchService search;
    private final JdbcClient jdbc;
    private final Clock clock;

    CfdService(BoardService boards, SearchService search, JdbcClient jdbc, Clock clock) {
        this.boards = boards;
        this.search = search;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Cfd compute(AuthUser me, UUID boardId, LocalDate from, LocalDate to, String fql, ZoneId zone) {
        Board b = boards.get(me, boardId);
        if (from.isAfter(to) || from.plusDays(366).isBefore(to)) throw Errors.badRequest("INVALID_PERIOD", "Période d'un an au plus, début avant fin");
        Map<UUID, Integer> column = new HashMap<>();
        for (int i = 0; i < b.getColumns().size(); i++) {
            int index = i;
            b.getColumns().get(i).getStatuses().forEach(s -> column.put(s.getId(), index));
        }
        List<UUID> spaceIds = boards.visibleSpaces(me, b).stream().map(Space::getId).toList();
        record Row(UUID id, UUID status, Instant created) {}
        List<Row> tickets = jdbc.sql("select id, status_id, created_at from tickets where space_id in (:spaces)").param("spaces", spaceIds)
                .query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("status_id", UUID.class), rs.getTimestamp("created_at").toInstant())).list();
        if (fql != null && !fql.isBlank()) {
            Set<UUID> kept = search.matching(me, fql, tickets.stream().map(Row::id).toList());
            tickets = tickets.stream().filter(t -> kept.contains(t.id())).toList();
        }
        // Status changes per ticket, oldest first.
        Map<UUID, List<Change>> changes = new HashMap<>();
        jdbc.sql("""
                        select h.ticket_id, h.at, h.old_value, h.new_value from history_entries h join tickets t on t.id = h.ticket_id
                        where t.space_id in (:spaces) and h.field = 'status' order by h.at""").param("spaces", spaceIds)
                .query((rs, i) -> Map.entry(rs.getObject("ticket_id", UUID.class), new Change(rs.getTimestamp("at").toInstant(),
                        uuid(rs.getString("old_value")), uuid(rs.getString("new_value")))))
                .list().forEach(e -> changes.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));

        int n = b.getColumns().size();
        List<Day> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Instant end = d.plusDays(1).atStartOfDay(zone).toInstant();
            int[] reached = new int[n];
            for (Row t : tickets) {
                if (t.created().isAfter(end)) continue;
                UUID status = statusAt(t.status(), changes.getOrDefault(t.id(), List.of()), end);
                Integer col = column.get(status);
                if (col == null) continue; // unmapped status: not on the board
                for (int i = 0; i <= col; i++) reached[i]++;
            }
            days.add(new Day(d, Arrays.stream(reached).boxed().toList()));
        }
        return new Cfd(b.getColumns().stream().map(BoardColumn::getName).toList(), days);
    }

    /** A status change of a ticket. */
    record Change(Instant at, UUID from, UUID to) {}

    /** Status at {@code end}: the last change before it, else the status before the first change; no change = current. */
    static UUID statusAt(UUID current, List<Change> history, Instant end) {
        if (history.isEmpty()) return current;
        UUID status = history.getFirst().from();
        for (Change c : history) {
            if (c.at().isAfter(end)) break;
            status = c.to();
        }
        return status;
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    LocalDate today(ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }
}
