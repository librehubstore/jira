package com.fluxo.board;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance criteria §4.10 (server side; WIP colours, swimlanes and quick filters are computed by the UI). */
class BoardTests extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    String key;
    JsonNode space;
    String boardId;
    MockHttpSession admin;

    @BeforeEach
    void setUp() {
        key = randomKey();
        space = createSpace(key, "Board");
        boardId = space.get("boards").get(0).get("id").asString();
        admin = admin();
    }

    @Test
    void defaultBoardHasThreeColumnsAndNoUnmappedStatus() {
        JsonNode b = read(get(admin, "/api/boards/" + boardId));
        assertThat(b.get("columns").valueStream().map(c -> c.get("name").asString())).containsExactly("À faire", "En cours", "Terminé");
        assertThat(b.get("unmappedStatuses")).isEmpty();
        assertThat(b.get("hideDoneDays").asInt()).isEqualTo(14);
    }

    @Test
    void dragToAnotherColumnTransitionsAndRecordsHistory() {
        String k = ticket("A");
        assertThat(post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", k, "statusId", status("En cours")))).hasStatusOk();
        JsonNode t = read(get(admin, "/api/tickets/" + k));
        assertThat(t.get("status").get("name").asString()).isEqualTo("En cours");
        assertThat(read(get(admin, "/api/tickets/" + k + "/history")).valueStream().map(h -> h.get("field").asString())).contains("status");
    }

    @Test
    void refusedMoveChangesNothing() {
        String viewer = createUser("viewer");
        addMember(key, viewer, "Lecteur");
        String k = ticket("A");
        assertThat(post(login(viewer, PASSWORD), "/api/boards/" + boardId + "/move", Map.of("ticketKey", k, "statusId", status("En cours"))))
                .hasStatus(403);
        assertThat(read(get(admin, "/api/tickets/" + k)).get("status").get("name").asString()).isEqualTo("À faire");
    }

    @Test
    void rankingBetweenTwoCards() {
        String a = ticket("A"), b = ticket("B"), c = ticket("C");
        assertThat(post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", c, "aboveKey", a, "belowKey", b))).hasStatusOk();
        assertThat(cardKeys()).containsExactly(a, c, b);
        post(admin, "/api/boards/" + boardId + "/send", Map.of("ticketKey", b, "top", true));
        assertThat(cardKeys()).containsExactly(b, a, c);
    }

    @Test
    void doneTicketsHideByResolutionDateNotLastEdit() {
        assertThat(patch(admin, "/api/boards/" + boardId, Map.of("hideDoneDays", 7))).hasStatusOk();
        String old = ticket("vieux"), recent = ticket("récent");
        post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", old, "statusId", status("Terminé")));
        post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", recent, "statusId", status("Terminé")));
        jdbc.sql("update tickets set resolved_at = now() - interval '10 days' where space_id = (select id from spaces where key = ?) and number = 1")
                .param(key).update();
        // A comment yesterday must not bring it back (spec §11).
        post(admin, "/api/tickets/" + old + "/comments", Map.of("body", "encore un mot"));
        assertThat(cardKeys()).containsExactly(recent);

        patch(admin, "/api/boards/" + boardId, Map.of("neverHide", true));
        assertThat(cardKeys()).contains(old, recent);
    }

    @Test
    void unmappedStatusIsReportedWithCount() {
        ticket("A");
        String doing = status("En cours");
        JsonNode b = read(get(admin, "/api/boards/" + boardId));
        List<Map<String, Object>> cols = new ArrayList<>();
        for (JsonNode c : b.get("columns")) {
            List<String> ids = new ArrayList<>();
            c.get("statuses").forEach(s -> { if (!s.get("id").asString().equals(doing)) ids.add(s.get("id").asString()); });
            Map<String, Object> col = new HashMap<>();
            col.put("id", c.get("id").asString());
            col.put("name", c.get("name").asString());
            col.put("statusIds", ids);
            cols.add(col);
        }
        assertThat(put(admin, "/api/boards/" + boardId + "/columns", cols)).hasStatusOk();
        String k = ticket("B");
        jdbc.sql("update tickets set status_id = ?::uuid where space_id = (select id from spaces where key = ?) and number = 2").params(doing, key).update();

        JsonNode after = read(get(admin, "/api/boards/" + boardId));
        assertThat(after.get("unmappedStatuses").get(0).get("name").asString()).isEqualTo("En cours");
        JsonNode cards = read(get(admin, "/api/boards/" + boardId + "/cards"));
        assertThat(cards.get("unmappedCount").asInt()).isEqualTo(1);
        assertThat(cards.get("cards").valueStream().map(c -> c.get("key").asString())).doesNotContain(k);
    }

    @Test
    void addingColumnCreatesStatusAndWipIsStored() {
        JsonNode b = read(get(admin, "/api/boards/" + boardId));
        List<Map<String, Object>> cols = new ArrayList<>();
        for (JsonNode c : b.get("columns")) {
            Map<String, Object> col = new HashMap<>();
            col.put("id", c.get("id").asString());
            col.put("name", c.get("name").asString());
            col.put("statusIds", c.get("statuses").valueStream().map(s -> s.get("id").asString()).toList());
            if (c.get("name").asString().equals("En cours")) col.put("wipMax", 3);
            cols.add(col);
            if (c.get("name").asString().equals("En cours")) {
                cols.add(new HashMap<>(Map.of("name", "En revue", "statusIds", List.of(), "newStatusName", "En revue")));
            }
        }
        JsonNode after = read(put(admin, "/api/boards/" + boardId + "/columns", cols));
        assertThat(after.get("columns").valueStream().map(c -> c.get("name").asString()))
                .containsExactly("À faire", "En cours", "En revue", "Terminé");
        assertThat(after.get("columns").get(1).get("wipMax").asInt()).isEqualTo(3);
        assertThat(read(get(admin, "/api/spaces/" + key)).get("statuses").valueStream().map(s -> s.get("name").asString()))
                .contains("En revue");
    }

    @Test
    void cardsCarryBadges() {
        String parent = ticket("P");
        String sub = read(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "S", "typeId", typeId("Sous-tâche"), "parent", parent))).get("key").asString();
        post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", sub, "statusId", status("Terminé")));
        post(admin, "/api/tickets/" + parent + "/comments", Map.of("body", "x"));
        String blocker = ticket("B");
        String blocks = read(get(admin, "/api/link-types")).valueStream().filter(l -> l.get("name").asString().equals("Blocks"))
                .findFirst().orElseThrow().get("id").asString();
        post(admin, "/api/tickets/" + blocker + "/links", Map.of("linkTypeId", blocks, "ticketKey", parent));

        JsonNode card = read(get(admin, "/api/boards/" + boardId + "/cards")).get("cards").valueStream()
                .filter(c -> c.get("key").asString().equals(parent)).findFirst().orElseThrow();
        assertThat(card.get("subtasks").get("done").asInt()).isEqualTo(1);
        assertThat(card.get("comments").asInt()).isEqualTo(1);
        assertThat(card.get("blocked").asBoolean()).isTrue();
    }

    @Test
    void twoThousandCardsLoadFast() {
        jdbc.sql("""
                insert into tickets (id, space_id, number, type_id, status_id, priority_id, summary, reporter_id, creator_id, rank)
                select gen_random_uuid(), s.id, 100 + g, (select id from ticket_types where name = 'Tâche'),
                       (select id from statuses where workflow_id = s.workflow_id order by position limit 1),
                       (select id from priorities where is_default), 'Ticket ' || g, u.id, u.id, lpad(g::text, 6, '0')
                from spaces s, users u, generate_series(1, 2000) g
                where s.key = ? and u.username = 'admin'
                """).param(key).update();
        get(admin, "/api/boards/" + boardId + "/cards"); // warm-up
        long start = System.nanoTime();
        JsonNode cards = read(get(admin, "/api/boards/" + boardId + "/cards"));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(cards.get("cards")).hasSize(2000);
        assertThat(ms).as("board data for 2000 cards took %d ms", ms).isLessThan(1500);
    }

    // --- helpers ---

    String ticket(String summary) {
        var r = post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", summary));
        assertThat(r).hasStatus(201);
        return read(r).get("key").asString();
    }

    String status(String name) {
        return space.get("statuses").valueStream().filter(s -> s.get("name").asString().equals(name))
                .findFirst().orElseThrow().get("id").asString();
    }

    String typeId(String name) {
        return space.get("ticketTypes").valueStream().filter(t -> t.get("name").asString().equals(name))
                .findFirst().orElseThrow().get("id").asString();
    }

    List<String> cardKeys() {
        return read(get(admin, "/api/boards/" + boardId + "/cards")).get("cards").valueStream().map(c -> c.get("key").asString()).toList();
    }
}
