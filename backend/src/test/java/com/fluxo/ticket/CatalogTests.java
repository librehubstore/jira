package com.fluxo.ticket;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §4.13: labels management, components (default assignee), versions (release from the board, FQL). */
class CatalogTests extends IntegrationTest {

    String key;
    JsonNode space;
    MockHttpSession admin;

    @BeforeEach
    void setUp() {
        key = randomKey();
        space = createSpace(key, "Catalogue");
        admin = admin();
    }

    @Test
    void labelsRenameMergeColourDelete() {
        String a = ticket("A", Map.of("labels", List.of("front", "ui"))), b = ticket("B", Map.of("labels", List.of("frontend")));
        Map<String, JsonNode> byName = labels();
        assertThat(byName.get("front").get("count").asInt()).isEqualTo(1);
        assertThat(patch(admin, "/api/labels/" + id(byName.get("front")), Map.of("name", "frontend"))).hasStatus(409);
        assertThat(post(admin, "/api/labels/" + id(byName.get("front")) + "/merge", Map.of("targetId", id(byName.get("frontend"))))).hasStatusOk();
        assertThat(patch(admin, "/api/labels/" + id(byName.get("ui")), Map.of("name", "interface", "color", "#22c55e"))).hasStatusOk();
        assertThat(read(get(admin, "/api/tickets/" + a)).get("labels").valueStream().map(JsonNode::asString)).containsExactly("frontend", "interface");
        assertThat(labels().get("interface").get("color").asString()).isEqualTo("#22c55e");
        assertThat(delete(admin, "/api/labels/" + id(labels().get("frontend")))).hasStatus(204);
        assertThat(read(get(admin, "/api/tickets/" + b)).get("labels")).isEmpty();
    }

    @Test
    void componentLeadIsTheDefaultAssignee() {
        String lead = createUser("lead");
        addMember(key, lead, "Membre");
        var r = post(admin, "/api/spaces/" + key + "/components", Map.of("name", "Paiement", "leadId", userIdByName(lead)));
        assertThat(r).hasStatus(201);
        String comp = id(read(r));
        assertThat(post(admin, "/api/spaces/" + key + "/components", Map.of("name", "paiement"))).hasStatus(409);
        JsonNode t = read(get(admin, "/api/tickets/" + ticket("A", Map.of("componentIds", List.of(comp)))));
        assertThat(t.get("assignee").get("username").asString()).isEqualTo(lead);
        assertThat(t.get("components").get(0).get("name").asString()).isEqualTo("Paiement");
        assertThat(fql("component = Paiement")).hasSize(1);
        patch(admin, "/api/components/" + comp, Map.of("archived", true));
        assertThat(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "x", "componentIds", List.of(comp)))).hasStatus(400);
    }

    @Test
    void releaseFromTheBoardTakesDoneTicketsOff() {
        String done = ticket("fini", Map.of()), open = ticket("ouvert", Map.of());
        String boardId = space.get("boards").get(0).get("id").asString();
        post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", done, "statusId", status("Terminé")));
        var r = post(admin, "/api/boards/" + boardId + "/release", Map.of("name", "1.0"));
        assertThat(r).hasStatusOk();
        assertThat(read(r).get("tickets").asInt()).isEqualTo(1);
        assertThat(read(get(admin, "/api/boards/" + boardId + "/cards")).get("cards").valueStream().map(c -> c.get("key").asString()))
                .containsExactly(open);
        JsonNode version = read(get(admin, "/api/spaces/" + key + "/versions")).get(0);
        assertThat(version.get("status").asString()).isEqualTo("RELEASED");
        assertThat(version.get("count").asInt()).isEqualTo(1);
        assertThat(fql("fixVersion in releasedVersions()")).containsExactly(done);
        assertThat(fql("fixVersion = \"1.0\"")).containsExactly(done);

        String member = createUser("viewer");
        addMember(key, member, "Lecteur");
        assertThat(post(login(member, PASSWORD), "/api/spaces/" + key + "/versions", Map.of("name", "2.0"))).hasStatus(403);
    }

    @Test
    void versionsMergeAndBulkEdit() {
        String v1 = id(read(post(admin, "/api/spaces/" + key + "/versions", Map.of("name", "1.1"))));
        String v2 = id(read(post(admin, "/api/spaces/" + key + "/versions", Map.of("name", "1.2"))));
        String a = ticket("A", Map.of("fixVersionIds", List.of(v1)));
        assertThat(post(admin, "/api/versions/" + v1 + "/merge", Map.of("targetId", v2))).hasStatusOk();
        assertThat(read(get(admin, "/api/tickets/" + a)).get("fixVersions").get(0).get("name").asString()).isEqualTo("1.2");
    }

    // --- helpers ---

    Map<String, JsonNode> labels() {
        Map<String, JsonNode> m = new HashMap<>();
        read(get(admin, "/api/spaces/" + key + "/label-catalog")).forEach(l -> m.put(l.get("name").asString(), l));
        return m;
    }

    List<String> fql(String q) {
        return read(get(admin, "/api/search?fql=space = " + key + " AND " + q)).get("rows").valueStream().map(r -> r.get("key").asString()).toList();
    }

    static String id(JsonNode n) {
        return n.get("id").asString();
    }

    String ticket(String summary, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("summary", summary);
        var r = post(admin, "/api/spaces/" + key + "/tickets", body);
        assertThat(r).hasStatus(201);
        return read(r).get("key").asString();
    }

    String status(String name) {
        return space.get("statuses").valueStream().filter(s -> s.get("name").asString().equals(name)).findFirst().orElseThrow().get("id").asString();
    }
}
