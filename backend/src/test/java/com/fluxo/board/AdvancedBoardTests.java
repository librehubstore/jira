package com.fluxo.board;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V1.3 board features (spec §4.10): several boards, multi-space, backlog, colours, lanes, blocking WIP. */
class AdvancedBoardTests extends IntegrationTest {

    String key;
    JsonNode space;
    String boardId;
    MockHttpSession admin;

    @BeforeEach
    void setUp() {
        key = randomKey();
        space = createSpace(key, "Board+");
        boardId = space.get("boards").get(0).get("id").asString();
        admin = admin();
    }

    @Test
    void severalBoardsOneDefault() {
        var r = post(admin, "/api/boards", Map.of("spaceKey", key, "name", "Support"));
        assertThat(r).hasStatus(201);
        String second = read(r).get("id").asString();
        assertThat(read(r).get("isDefault").asBoolean()).isFalse();
        assertThat(delete(admin, "/api/boards/" + boardId)).hasStatus(409);
        assertThat(post(admin, "/api/boards/" + second + "/default", Map.of())).hasStatusOk();
        JsonNode boards = read(get(admin, "/api/spaces/" + key)).get("boards");
        assertThat(boards.valueStream().filter(b -> b.get("isDefault").asBoolean()).map(b -> b.get("id").asString())).containsExactly(second);
        assertThat(delete(admin, "/api/boards/" + boardId)).hasStatus(204);
    }

    @Test
    void multiSpaceBoardShowsAndMovesTicketsOfBothSpaces() {
        String other = randomKey();
        JsonNode otherSpace = createSpace(other, "Autre");
        String mine = ticket(key, "ici");
        String theirs = ticket(other, "là-bas");
        assertThat(patch(admin, "/api/boards/" + boardId, Map.of("otherSpaceKeys", List.of(other)))).hasStatusOk();

        JsonNode b = read(get(admin, "/api/boards/" + boardId));
        assertThat(b.get("spaceKeys").valueStream().map(JsonNode::asString)).containsExactly(key, other);
        // The other space's statuses are unmapped until added to a column.
        assertThat(b.get("unmappedStatuses")).hasSize(3);
        assertThat(cardKeys(false)).containsExactly(mine);
        List<Map<String, Object>> cols = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            JsonNode c = b.get("columns").get(i);
            cols.add(Map.of("id", c.get("id").asString(), "name", c.get("name").asString(),
                    "statusIds", List.of(c.get("statuses").get(0).get("id").asString(), status(otherSpace, c.get("name").asString()))));
        }
        assertThat(put(admin, "/api/boards/" + boardId + "/columns", cols)).hasStatusOk();
        assertThat(cardKeys(false)).containsExactlyInAnyOrder(mine, theirs);

        // Dropped in « En cours » (first status = this space's): the other ticket takes its own workflow's status.
        assertThat(post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", theirs, "statusId", status(space, "En cours")))).hasStatusOk();
        assertThat(read(get(admin, "/api/tickets/" + theirs)).get("status").get("id").asString()).isEqualTo(status(otherSpace, "En cours"));
        // Spaces the user cannot browse are left out.
        MockHttpSession member = login(createUser("solo"), PASSWORD);
        addMember(key, userIdOf(member), "Membre");
        assertThat(read(get(member, "/api/boards/" + boardId + "/cards")).get("cards").valueStream().map(c -> c.get("key").asString()))
                .containsExactly(mine);
    }

    @Test
    void backlogTakesTheFirstColumnOffTheBoard() {
        String a = ticket(key, "à planifier");
        String b = ticket(key, "sélectionné");
        String c = ticket(key, "en cours");
        // Four columns: Backlog (À faire) | Sélectionné (new status) | En cours | Terminé.
        JsonNode bv = read(get(admin, "/api/boards/" + boardId));
        List<Map<String, Object>> cols = new java.util.ArrayList<>();
        cols.add(Map.of("id", bv.get("columns").get(0).get("id").asString(), "name", "Backlog", "statusIds", List.of(status(space, "À faire"))));
        cols.add(Map.of("name", "Sélectionné", "statusIds", List.of(), "newStatusName", "Sélectionné"));
        cols.add(Map.of("id", bv.get("columns").get(1).get("id").asString(), "name", "En cours", "statusIds", List.of(status(space, "En cours"))));
        cols.add(Map.of("id", bv.get("columns").get(2).get("id").asString(), "name", "Terminé", "statusIds", List.of(status(space, "Terminé"))));
        JsonNode updated = read(put(admin, "/api/boards/" + boardId + "/columns", cols));
        String selected = updated.get("columns").get(1).get("statuses").get(0).get("id").asString();
        post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", b, "statusId", selected));
        post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", c, "statusId", status(space, "En cours")));

        assertThat(patch(admin, "/api/boards/" + boardId, Map.of("backlogEnabled", true))).hasStatusOk();
        assertThat(cardKeys(false)).containsExactlyInAnyOrder(b, c);
        assertThat(cardKeys(true)).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void blockingWipRefusesTheDrop() {
        String a = ticket(key, "A"), b = ticket(key, "B");
        JsonNode bv = read(get(admin, "/api/boards/" + boardId));
        List<Map<String, Object>> cols = new java.util.ArrayList<>();
        for (JsonNode c : bv.get("columns")) {
            Map<String, Object> m = new HashMap<>(Map.of("id", c.get("id").asString(), "name", c.get("name").asString(),
                    "statusIds", List.of(c.get("statuses").get(0).get("id").asString())));
            if (c.get("name").asString().equals("En cours")) m.put("wipMax", 1);
            cols.add(m);
        }
        put(admin, "/api/boards/" + boardId + "/columns", cols);
        assertThat(post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", a, "statusId", status(space, "En cours")))).hasStatusOk();
        // Indicative by default: allowed.
        assertThat(patch(admin, "/api/boards/" + boardId, Map.of("wipBlocking", true))).hasStatusOk();
        assertThat(post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", b, "statusId", status(space, "En cours"))))
                .hasStatus(409).bodyJson().hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("WIP_LIMIT"));
        assertThat(read(get(admin, "/api/tickets/" + b)).get("status").get("name").asString()).isEqualTo("À faire");
    }

    @Test
    void draggingToAnotherLaneSetsTheField() {
        String bob = createUser("bob");
        addMember(key, bob, "Membre");
        String k = ticket(key, "A");
        assertThat(post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", k,
                "lane", Map.of("field", "ASSIGNEE", "value", userIdByName(bob))))).hasStatusOk();
        assertThat(read(get(admin, "/api/tickets/" + k)).get("assignee").get("username").asString()).isEqualTo(bob);
        assertThat(post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", k,
                "lane", Map.of("field", "ASSIGNEE")))).hasStatusOk();
        assertThat(read(get(admin, "/api/tickets/" + k)).get("assignee").isNull()).isTrue();
    }

    @Test
    void colourQueriesAndDaysInColumn() {
        assertThat(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "bug", "typeId", type("Bug")))).hasStatus(201);
        ticket(key, "tâche");
        assertThat(patch(admin, "/api/boards/" + boardId, Map.of("cardColorMode", "QUERIES",
                "cardColorQueries", List.of(Map.of("fql", "type = Bug", "color", "#dc2626")), "cardFields", List.of("daysInColumn"),
                "workingDays", List.of(1, 2, 3, 4)))).hasStatusOk();
        assertThat(patch(admin, "/api/boards/" + boardId, Map.of("cardColorQueries", List.of(Map.of("fql", "type = Bug", "color", "red")))))
                .hasStatus(400);
        Map<String, JsonNode> cards = new HashMap<>();
        read(get(admin, "/api/boards/" + boardId + "/cards")).get("cards").forEach(c -> cards.put(c.get("summary").asString(), c));
        assertThat(cards.get("bug").get("color").asString()).isEqualTo("#dc2626");
        assertThat(cards.get("tâche").get("color").isNull()).isTrue();
        assertThat(cards.get("tâche").get("statusSince").asString()).isNotBlank();
        assertThat(read(get(admin, "/api/boards/" + boardId)).get("workingDays").valueStream().map(JsonNode::asInt)).containsExactly(1, 2, 3, 4);
    }

    // --- helpers ---

    String ticket(String spaceKey, String summary) {
        var r = post(admin, "/api/spaces/" + spaceKey + "/tickets", Map.of("summary", summary));
        assertThat(r).hasStatus(201);
        return read(r).get("key").asString();
    }

    static String status(JsonNode space, String name) {
        return space.get("statuses").valueStream().filter(s -> s.get("name").asString().equals(name))
                .findFirst().orElseThrow().get("id").asString();
    }

    String type(String name) {
        return space.get("ticketTypes").valueStream().filter(t -> t.get("name").asString().equals(name)).findFirst().orElseThrow().get("id").asString();
    }

    String userIdOf(MockHttpSession session) {
        return read(get(session, "/api/me")).get("username").asString();
    }

    List<String> cardKeys(boolean backlog) {
        return read(get(admin, "/api/boards/" + boardId + "/cards?backlog=" + backlog)).get("cards").valueStream().map(c -> c.get("key").asString()).toList();
    }
}
