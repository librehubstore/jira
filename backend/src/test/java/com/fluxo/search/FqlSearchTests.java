package com.fluxo.search;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance criteria §4.11 (FQL, saved filters) and FQL on boards §4.10. */
class FqlSearchTests extends IntegrationTest {

    String key;
    JsonNode space;
    MockHttpSession admin;

    @BeforeEach
    void setUp() {
        key = randomKey();
        space = createSpace(key, "FQL");
        admin = admin();
    }

    @Test
    void myOpenTicketsByPriority() {
        String me = userIdByName("admin");
        String low = ticket(Map.of("summary", "bas", "assigneeId", me, "priorityId", priority("Basse")));
        String high = ticket(Map.of("summary", "haut", "assigneeId", me, "priorityId", priority("Haute")));
        String done = ticket(Map.of("summary", "fini", "assigneeId", me));
        transition(done, "Terminé");
        ticket(Map.of("summary", "pas à moi"));

        assertThat(fql("space = " + key + " AND assignee = currentUser() AND statusCategory != Done ORDER BY priority DESC"))
                .containsExactly(high, low);
    }

    @Test
    void wasAndChangedUseTheHistory() {
        String a = ticket(Map.of("summary", "A"));
        String b = ticket(Map.of("summary", "B"));
        transition(a, "En cours");
        transition(a, "Terminé");
        assertThat(fql("space = " + key + " AND status WAS \"En cours\" AND resolved >= -7d")).containsExactly(a);
        assertThat(fql("space = " + key + " AND status CHANGED FROM \"À faire\" TO \"En cours\" BY admin AFTER startOfDay()")).containsExactly(a);
        assertThat(fql("space = " + key + " AND status WAS NOT \"En cours\"")).containsExactly(b);
    }

    @Test
    void textLabelsHierarchyAndLinks() {
        String epic = ticket(Map.of("summary", "Refonte paiement", "typeId", type("Epic")));
        String child = ticket(Map.of("summary", "Webhook Stripe", "parent", epic, "labels", List.of("front-end", "urgent")));
        String other = ticket(Map.of("summary", "Autre", "description", "Rien à voir"));
        String blocks = read(get(admin, "/api/link-types")).valueStream().filter(l -> l.get("name").asString().equals("Blocks"))
                .findFirst().orElseThrow().get("id").asString();
        post(admin, "/api/tickets/" + other + "/links", Map.of("linkTypeId", blocks, "ticketKey", child));

        assertThat(fql("space = " + key + " AND summary ~ \"webho\"")).containsExactly(child);
        assertThat(fql("space = " + key + " AND labels ~ \"front*\"")).containsExactly(child);
        assertThat(fql("space = " + key + " AND labels IS EMPTY ORDER BY key")).containsExactly(epic, other);
        assertThat(fql("space = " + key + " AND hasChildren = true")).containsExactly(epic);
        assertThat(fql("space = " + key + " AND parent = " + epic)).containsExactly(child);
        assertThat(fql("key in linkedTickets(" + child + ", \"est bloqué par\")")).containsExactly(other);
        assertThat(fql("space = " + key + " AND issueLinkType = bloque")).containsExactly(other);
        assertThat(fql("space = " + key + " AND issueLink = " + other)).containsExactly(child);
    }

    @Test
    void syntaxErrorsArePositioned() {
        var r = get(admin, "/api/search?fql=" + enc("assignee = "));
        assertThat(r).hasStatus(400).bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("FQL_ERROR"))
                .hasPathSatisfying("$.position", v -> v.assertThat().isEqualTo(11));
        assertThat(get(admin, "/api/search?fql=" + enc("couleur = rouge"))).hasStatus(400).bodyJson()
                .hasPathSatisfying("$.detail", v -> v.assertThat().asString().contains("Champ inconnu « couleur »"));
    }

    @Test
    void neverReturnsInvisibleTickets() {
        ticket(Map.of("summary", "secret"));
        MockHttpSession outsider = login(createUser("nosy"), PASSWORD);
        assertThat(read(get(outsider, "/api/search?fql=" + enc("space = " + key))).get("total").asInt()).isZero();
    }

    @Test
    void savedFiltersAreSharedFavouritedAndOwned() {
        String bob = createUser("bob");
        String groupId = idOf(post(admin, "/api/admin/groups", Map.of("name", unique("grp"))));
        put(admin, "/api/admin/groups/" + groupId + "/members", List.of(userIdByName(bob)));
        MockHttpSession bobS = login(bob, PASSWORD);

        assertThat(post(admin, "/api/filters", Map.of("name", "Cassé", "fql", "assignee = "))).hasStatus(400);
        String priv = idOf(post(admin, "/api/filters", Map.of("name", "Privé", "fql", "assignee = currentUser()")));
        String shared = idOf(post(admin, "/api/filters", Map.of("name", "Équipe", "fql", "statusCategory = TODO",
                "shareScope", "GROUP", "shareGroupId", groupId)));

        List<String> seen = read(get(bobS, "/api/filters")).valueStream().map(f -> f.get("id").asString()).toList();
        assertThat(seen).contains(shared).doesNotContain(priv);
        assertThat(put(bobS, "/api/filters/" + shared + "/favorite", Map.of())).hasStatusOk();
        assertThat(read(get(bobS, "/api/filters/" + shared)).get("favorite").asBoolean()).isTrue();
        assertThat(patch(bobS, "/api/filters/" + shared, Map.of("name", "x", "fql", "key = X-1"))).hasStatus(403);
        assertThat(get(bobS, "/api/filters/" + priv)).hasStatus(404);
    }

    @Test
    void boardBaseFilterLanesAndMatch() {
        String boardId = space.get("boards").get(0).get("id").asString();
        String bug = ticket(Map.of("summary", "bug", "typeId", type("Bug")));
        String urgent = ticket(Map.of("summary", "urgent", "priorityId", priority("Bloquante")));
        String task = ticket(Map.of("summary", "tâche"));

        assertThat(patch(admin, "/api/boards/" + boardId, Map.of("swimlaneMode", "QUERIES", "swimlaneQueries", List.of(
                Map.of("name", "Expedite", "fql", "priority = Bloquante"), Map.of("name", "Bugs", "fql", "type = Bug"))))).hasStatusOk();
        Map<String, Integer> lanes = new HashMap<>();
        read(get(admin, "/api/boards/" + boardId + "/cards")).get("cards").forEach(c -> lanes.put(c.get("key").asString(), c.get("lane").asInt()));
        assertThat(lanes).containsEntry(urgent, 0).containsEntry(bug, 1).containsEntry(task, -1);

        assertThat(read(get(admin, "/api/boards/" + boardId + "/match?fql=" + enc("type = Bug"))).size()).isEqualTo(1);
        assertThat(patch(admin, "/api/boards/" + boardId, Map.of("baseFql", "type != Bug"))).hasStatusOk();
        assertThat(read(get(admin, "/api/boards/" + boardId + "/cards")).get("cards").valueStream().map(c -> c.get("key").asString()))
                .containsExactlyInAnyOrder(urgent, task);
        assertThat(patch(admin, "/api/boards/" + boardId, Map.of("baseFql", "type = "))).hasStatus(400);
    }

    // --- helpers ---

    List<String> fql(String query) {
        var r = get(admin, "/api/search?fql=" + enc(query));
        if (r.getResponse().getStatus() != 200) {
            throw new AssertionError(query + " -> " + new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
        }
        return read(r).get("rows").valueStream().map(n -> n.get("key").asString()).toList();
    }

    static String enc(String s) {
        return s; // MockMvcTester.uri(String) encodes the query itself
    }

    String ticket(Map<String, Object> body) {
        var r = post(admin, "/api/spaces/" + key + "/tickets", new HashMap<>(body));
        assertThat(r).hasStatus(201);
        return read(r).get("key").asString();
    }

    void transition(String k, String status) {
        String id = space.get("statuses").valueStream().filter(s -> s.get("name").asString().equals(status)).findFirst().orElseThrow().get("id").asString();
        assertThat(post(admin, "/api/tickets/" + k + "/transition", Map.of("statusId", id))).hasStatusOk();
    }

    String type(String name) {
        return space.get("ticketTypes").valueStream().filter(t -> t.get("name").asString().equals(name)).findFirst().orElseThrow().get("id").asString();
    }

    String priority(String name) {
        return read(get(admin, "/api/meta")).get("priorities").valueStream().filter(p -> p.get("name").asString().equals(name))
                .findFirst().orElseThrow().get("id").asString();
    }
}
