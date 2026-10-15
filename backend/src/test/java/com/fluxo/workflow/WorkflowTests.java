package com.fluxo.workflow;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance criteria §4.9: transitions with conditions, validators, actions, screens; per-type workflows. */
class WorkflowTests extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    String key;
    JsonNode space;
    String wf;
    MockHttpSession admin;

    @BeforeEach
    void setUp() {
        key = randomKey();
        space = createSpace(key, "WF");
        admin = admin();
        wf = read(get(admin, "/api/spaces/" + key + "/workflows")).get("defaultWorkflowId").asString();
    }

    @Test
    void simplifiedWorkflowOffersEveryOtherStatus() {
        String k = ticket("A");
        assertThat(read(get(admin, "/api/tickets/" + k + "/transitions")).valueStream().map(t -> t.get("name").asString()))
                .containsExactlyInAnyOrder("En cours", "Terminé");
    }

    @Test
    void conditionsValidatorsAndScreen() {
        String alice = createUser("alice"), bob = createUser("bob");
        addMember(key, alice, "Membre");
        addMember(key, bob, "Membre");
        setTransitions(List.of(
                Map.of("name", "Démarrer", "fromStatusIds", List.of(status("À faire")), "toStatusId", status("En cours")),
                Map.of("name", "Valider", "fromStatusIds", List.of(status("En cours")), "toStatusId", status("Terminé"),
                        "rules", Map.of("conditionMode", "ALL",
                                "conditions", List.of(Map.of("type", "ASSIGNEE_ONLY")),
                                "validators", List.of(Map.of("type", "COMMENT_REQUIRED")),
                                "screen", Map.of("resolution", true, "comment", true))),
                Map.of("name", "Rouvrir", "fromStatusIds", List.of(), "toStatusId", status("À faire"))));
        String k = ticket("A", Map.of("assigneeId", userIdByName(alice)));
        MockHttpSession aliceS = login(alice, PASSWORD), bobS = login(bob, PASSWORD);

        // No transition from « À faire » straight to « Terminé ».
        assertThat(transition(aliceS, k, "Terminé", null)).hasStatus(400);
        assertThat(transition(bobS, k, "En cours", null)).hasStatusOk();

        // « Valider » is the assignee's only: bob neither sees it nor can call it, nor drop the card there.
        assertThat(names(bobS, k)).containsExactly("Rouvrir");
        assertThat(transition(bobS, k, "Terminé", "ok")).hasStatus(403);
        String boardId = space.get("boards").get(0).get("id").asString();
        assertThat(post(bobS, "/api/boards/" + boardId + "/move", Map.of("ticketKey", k, "statusId", status("Terminé")))).hasStatus(403);

        JsonNode valider = read(get(aliceS, "/api/tickets/" + k + "/transitions")).valueStream()
                .filter(t -> t.get("name").asString().equals("Valider")).findFirst().orElseThrow();
        assertThat(valider.get("commentRequired").asBoolean()).isTrue();
        assertThat(valider.get("screen").get("resolution").asBoolean()).isTrue();
        assertThat(transition(aliceS, k, "Terminé", null)).hasStatus(400).bodyJson()
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("COMMENT_REQUIRED"));
        assertThat(transition(aliceS, k, "Terminé", "Validé en recette")).hasStatusOk();
        assertThat(read(get(admin, "/api/tickets/" + k + "/comments")).valueStream().map(c -> c.get("body").asString()))
                .contains("Validé en recette");
    }

    @Test
    void postTransitionActions() {
        setTransitions(List.of(Map.of("name", "Prendre", "fromStatusIds", List.of(), "toStatusId", status("En cours"),
                "rules", Map.of("actions", List.of(Map.of("type", "ASSIGN_ME"),
                        Map.of("type", "SET_FIELD", "field", "dueDate", "value", "\"2026-12-01\""),
                        Map.of("type", "CLEAR_FIELD", "field", "labels"))))));
        String k = ticket("A", Map.of("labels", List.of("x")));
        assertThat(transition(admin, k, "En cours", null)).hasStatusOk();
        JsonNode t = read(get(admin, "/api/tickets/" + k));
        assertThat(t.get("assignee").get("username").asString()).isEqualTo("admin");
        assertThat(t.get("dueDate").asString()).isEqualTo("2026-12-01");
        assertThat(t.get("labels")).isEmpty();
        assertThat(setTransitionsRaw(List.of(Map.of("name", "X", "toStatusId", status("En cours"),
                "rules", Map.of("actions", List.of(Map.of("type", "WEBHOOK", "value", "ftp://x"))))))).hasStatus(400);
    }

    @Test
    void statusInUseNeedsAReplacement() {
        String k = ticket("A");
        transition(admin, k, "En cours", null);
        var r = delete(admin, "/api/workflows/" + wf + "/statuses/" + status("En cours"));
        assertThat(r).hasStatus(409).bodyJson().hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("STATUS_IN_USE"));
        assertThat(delete(admin, "/api/workflows/" + wf + "/statuses/" + status("En cours") + "?migrateTo=" + status("À faire"))).hasStatusOk();
        assertThat(read(get(admin, "/api/tickets/" + k)).get("status").get("name").asString()).isEqualTo("À faire");
    }

    @Test
    void ownWorkflowPerType() {
        String bugType = space.get("ticketTypes").valueStream().filter(t -> t.get("name").asString().equals("Bug")).findFirst().orElseThrow().get("id").asString();
        String bug = ticket("bug", Map.of("typeId", bugType));
        transition(admin, bug, "En cours", null);
        JsonNode r = read(post(admin, "/api/spaces/" + key + "/workflows/types/" + bugType, Map.of()));
        String bugWf = r.get("types").valueStream().filter(t -> t.get("typeId").asString().equals(bugType)).findFirst().orElseThrow().get("workflowId").asString();
        assertThat(bugWf).isNotEqualTo(wf);
        JsonNode t = read(get(admin, "/api/tickets/" + bug));
        assertThat(t.get("status").get("name").asString()).isEqualTo("En cours");
        assertThat(t.get("status").get("id").asString()).isNotEqualTo(status("En cours"));
        // The board keeps showing it: the copied statuses joined the same-named columns.
        String boardId = space.get("boards").get(0).get("id").asString();
        assertThat(read(get(admin, "/api/boards/" + boardId + "/cards")).get("cards").valueStream().map(c -> c.get("key").asString())).contains(bug);

        assertThat(delete(admin, "/api/spaces/" + key + "/workflows/types/" + bugType)).hasStatusOk();
        assertThat(read(get(admin, "/api/tickets/" + bug)).get("status").get("id").asString()).isEqualTo(status("En cours"));
    }

    @Test
    void sharedWorkflowIsEditedByTheGlobalAdminOnly() {
        String lead = createUser("lead");
        addMember(key, lead, "Admin");
        MockHttpSession leadS = login(lead, PASSWORD);
        assertThat(put(leadS, "/api/workflows/" + wf + "/transitions", List.of())).hasStatusOk();
        jdbc.sql("update workflows set shared = true where id = ?::uuid").param(wf).update();
        assertThat(put(leadS, "/api/workflows/" + wf + "/transitions", List.of())).hasStatus(403);
        assertThat(put(admin, "/api/workflows/" + wf + "/transitions", List.of())).hasStatusOk();
    }

    // --- helpers ---

    void setTransitions(List<Map<String, Object>> specs) {
        assertThat(setTransitionsRaw(specs)).hasStatusOk();
    }

    org.springframework.test.web.servlet.assertj.MvcTestResult setTransitionsRaw(List<Map<String, Object>> specs) {
        return put(admin, "/api/workflows/" + wf + "/transitions", specs);
    }

    org.springframework.test.web.servlet.assertj.MvcTestResult transition(MockHttpSession s, String k, String status, String comment) {
        Map<String, Object> body = new HashMap<>(Map.of("statusId", status(status)));
        if (comment != null) body.put("comment", comment);
        return post(s, "/api/tickets/" + k + "/transition", body);
    }

    List<String> names(MockHttpSession s, String k) {
        return read(get(s, "/api/tickets/" + k + "/transitions")).valueStream().map(t -> t.get("name").asString()).toList();
    }

    String ticket(String summary) {
        return ticket(summary, Map.of());
    }

    String ticket(String summary, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("summary", summary);
        var r = post(admin, "/api/spaces/" + key + "/tickets", body);
        assertThat(r).hasStatus(201);
        return read(r).get("key").asString();
    }

    String status(String name) {
        return space.get("statuses").valueStream().filter(s -> s.get("name").asString().equals(name))
                .findFirst().orElseThrow().get("id").asString();
    }
}
