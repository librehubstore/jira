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

/** Acceptance criteria §4.3 (tickets), §4.8 (history), §4.9 (simplified workflow). */
class TicketTests extends IntegrationTest {

    String key;
    JsonNode space;
    MockHttpSession admin;

    @BeforeEach
    void setUp() {
        key = randomKey();
        space = createSpace(key, "Tickets");
        admin = admin();
    }

    @Test
    void quickCreateWithOnlySummaryAppliesDefaults() {
        JsonNode t = create(Map.of("summary", "  Corriger le login  "));
        assertThat(t.get("key").asString()).isEqualTo(key + "-1");
        assertThat(t.get("summary").asString()).isEqualTo("Corriger le login");
        assertThat(t.get("type").get("name").asString()).isEqualTo("Tâche");
        assertThat(t.get("priority").get("name").asString()).isEqualTo("Moyenne");
        assertThat(t.get("status").get("category").asString()).isEqualTo("TODO");
        assertThat(t.get("reporter").get("username").asString()).isEqualTo("admin");
    }

    @Test
    void emptySummaryIsRefused() {
        assertThat(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "   ")))
                .hasStatus(400).bodyJson().hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("SUMMARY_REQUIRED"));
    }

    @Test
    void doneSetsResolutionAndReopeningClearsIt() {
        String k = create(Map.of("summary", "A")).get("key").asString();
        transition(k, "En cours");
        JsonNode done = transition(k, "Terminé");
        assertThat(done.get("resolution").get("name").asString()).isEqualTo("Fait");
        assertThat(done.get("resolvedAt").isNull()).isFalse();

        JsonNode reopened = transition(k, "En cours");
        assertThat(reopened.get("resolution").isNull()).isTrue();
        assertThat(reopened.get("resolvedAt").isNull()).isTrue();
    }

    @Test
    void subtaskRequiresParentAndHierarchyIsEnforced() {
        assertThat(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "S", "typeId", typeId("Sous-tâche"))))
                .hasStatus(400).bodyJson().hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("PARENT_REQUIRED"));

        String epic = create(Map.of("summary", "E", "typeId", typeId("Epic"))).get("key").asString();
        String task = create(Map.of("summary", "T")).get("key").asString();
        // A subtask cannot hang under an epic, an epic cannot have a parent.
        assertThat(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "S", "typeId", typeId("Sous-tâche"), "parent", epic)))
                .hasStatus(400);
        assertThat(patch(admin, "/api/tickets/" + epic, Map.of("parent", task))).hasStatus(400);
        assertThat(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "S", "typeId", typeId("Sous-tâche"), "parent", task)))
                .hasStatus(201);
    }

    @Test
    void epicShowsChildrenProgress() {
        String epic = create(Map.of("summary", "Epic", "typeId", typeId("Epic"))).get("key").asString();
        String a = create(Map.of("summary", "A", "parent", epic)).get("key").asString();
        create(Map.of("summary", "B", "parent", epic));
        create(Map.of("summary", "C", "parent", epic));
        transition(a, "Terminé");

        JsonNode e = read(get(admin, "/api/tickets/" + epic));
        assertThat(e.get("progress").get("done").asInt()).isEqualTo(1);
        assertThat(e.get("progress").get("total").asInt()).isEqualTo(3);
    }

    @Test
    void fieldChangesAreRecordedInHistory() {
        String k = create(Map.of("summary", "A")).get("key").asString();
        assertThat(patch(admin, "/api/tickets/" + k, Map.of("priorityId", priorityId("Haute")))).hasStatusOk();

        JsonNode h = read(get(admin, "/api/tickets/" + k + "/history"));
        JsonNode last = h.get(h.size() - 1);
        assertThat(last.get("field").asString()).isEqualTo("priority");
        assertThat(last.get("oldDisplay").asString()).isEqualTo("Moyenne");
        assertThat(last.get("newDisplay").asString()).isEqualTo("Haute");
        assertThat(last.get("actor").get("username").asString()).isEqualTo("admin");
    }

    @Test
    void ticketNumbersAreNeverReused() {
        create(Map.of("summary", "1"));
        create(Map.of("summary", "2"));
        String third = create(Map.of("summary", "3")).get("key").asString();
        assertThat(delete(admin, "/api/tickets/" + third)).hasStatus(204);
        assertThat(create(Map.of("summary", "4")).get("key").asString()).isEqualTo(key + "-4");
    }

    @Test
    void spaceDefaultsApplyPerTicketType() {
        assertThat(put(admin, "/api/spaces/" + key + "/field-defaults", List.of(
                Map.of("field", "labels", "value", List.of("triage")),
                Map.of("ticketTypeId", typeId("Bug"), "field", "priorityId", "value", priorityId("Haute"))))).hasStatusOk();

        JsonNode bug = create(Map.of("summary", "Crash", "typeId", typeId("Bug")));
        assertThat(bug.get("priority").get("name").asString()).isEqualTo("Haute");
        assertThat(bug.get("labels").get(0).asString()).isEqualTo("triage");
        JsonNode task = create(Map.of("summary", "Doc"));
        assertThat(task.get("priority").get("name").asString()).isEqualTo("Moyenne");
        // Explicit values win over defaults.
        assertThat(create(Map.of("summary", "x", "typeId", typeId("Bug"), "priorityId", priorityId("Basse")))
                .get("priority").get("name").asString()).isEqualTo("Basse");
    }

    @Test
    void staleVersionGetsConflict() {
        JsonNode t = create(Map.of("summary", "A"));
        String k = t.get("key").asString();
        int v = t.get("version").asInt();
        assertThat(patch(admin, "/api/tickets/" + k, Map.of("summary", "B", "version", v))).hasStatusOk();
        assertThat(patch(admin, "/api/tickets/" + k, Map.of("summary", "C", "version", v)))
                .hasStatus(409).bodyJson().hasPathSatisfying("$.code", c -> c.assertThat().isEqualTo("TICKET_CONFLICT"));
    }

    @Test
    void onlyAssignableMembersCanBeAssigned() {
        String outsider = createUser("outsider");
        String member = createUser("assignee");
        addMember(key, member, "Membre");
        String k = create(Map.of("summary", "A")).get("key").asString();

        assertThat(patch(admin, "/api/tickets/" + k, Map.of("assigneeId", userIdByName(outsider))))
                .hasStatus(400).bodyJson().hasPathSatisfying("$.code", c -> c.assertThat().isEqualTo("NOT_ASSIGNABLE"));
        assertThat(patch(admin, "/api/tickets/" + k, Map.of("assigneeId", userIdByName(member)))).hasStatusOk();
        assertThat(read(get(admin, "/api/spaces/" + key + "/assignable")).valueStream()
                .map(u -> u.get("username").asString())).contains(member).doesNotContain(outsider);
    }

    @Test
    void permissionsAreEnforcedOnTickets() {
        String memberName = createUser("member");
        String viewerName = createUser("viewer");
        addMember(key, memberName, "Membre");
        addMember(key, viewerName, "Lecteur");
        String k = create(Map.of("summary", "A")).get("key").asString();
        MockHttpSession member = login(memberName, PASSWORD), viewer = login(viewerName, PASSWORD);

        assertThat(get(viewer, "/api/tickets/" + k)).hasStatusOk();
        assertThat(patch(viewer, "/api/tickets/" + k, Map.of("summary", "x"))).hasStatus(403);
        assertThat(patch(member, "/api/tickets/" + k, Map.of("summary", "x"))).hasStatusOk();
        assertThat(delete(member, "/api/tickets/" + k)).hasStatus(403);
        assertThat(get(login(createUser("outsider"), PASSWORD), "/api/tickets/" + k)).hasStatus(404);
    }

    @Test
    void movingKeepsOldKeyResolvable() {
        String other = randomKey();
        createSpace(other, "Cible");
        String k = create(Map.of("summary", "A", "labels", List.of("legacy"))).get("key").asString();
        String sub = create(Map.of("summary", "S", "typeId", typeId("Sous-tâche"), "parent", k)).get("key").asString();

        JsonNode preview = read(get(admin, "/api/tickets/" + k + "/move-preview?spaceKey=" + other));
        assertThat(preview.get("subtasksMoved").get(0).asString()).isEqualTo(sub);

        JsonNode moved = read(post(admin, "/api/tickets/" + k + "/move", Map.of("spaceKey", other)));
        assertThat(moved.get("key").asString()).isEqualTo(other + "-1");
        assertThat(moved.get("labels").get(0).asString()).isEqualTo("legacy");
        assertThat(read(get(admin, "/api/tickets/" + k)).get("key").asString()).isEqualTo(other + "-1");
        assertThat(read(get(admin, "/api/tickets/" + sub)).get("key").asString()).isEqualTo(other + "-2");
    }

    @Test
    void deletingParentRequiresChoiceForSubtasks() {
        String k = create(Map.of("summary", "P")).get("key").asString();
        String sub = create(Map.of("summary", "S", "typeId", typeId("Sous-tâche"), "parent", k)).get("key").asString();
        assertThat(delete(admin, "/api/tickets/" + k)).hasStatus(409);
        assertThat(delete(admin, "/api/tickets/" + k + "?withSubtasks=true")).hasStatus(204);
        assertThat(get(admin, "/api/tickets/" + sub)).hasStatus(404);
    }

    @Test
    void changeTypeChecksChildren() {
        String epic = create(Map.of("summary", "E", "typeId", typeId("Epic"))).get("key").asString();
        create(Map.of("summary", "child", "parent", epic));
        assertThat(patch(admin, "/api/tickets/" + epic, Map.of("typeId", typeId("Bug")))).hasStatus(400);
        String lone = create(Map.of("summary", "lone")).get("key").asString();
        assertThat(patch(admin, "/api/tickets/" + lone, Map.of("typeId", typeId("Bug")))).hasStatusOk();
    }

    // --- helpers ---

    JsonNode create(Map<String, Object> body) {
        var r = post(admin, "/api/spaces/" + key + "/tickets", new HashMap<>(body));
        assertThat(r).hasStatus(201);
        return read(r);
    }

    JsonNode transition(String ticketKey, String statusName) {
        String statusId = space.get("statuses").valueStream().filter(s -> s.get("name").asString().equals(statusName))
                .findFirst().orElseThrow().get("id").asString();
        var r = post(admin, "/api/tickets/" + ticketKey + "/transition", Map.of("statusId", statusId));
        assertThat(r).hasStatusOk();
        return read(r);
    }

    String typeId(String name) {
        return space.get("ticketTypes").valueStream().filter(t -> t.get("name").asString().equals(name))
                .findFirst().orElseThrow().get("id").asString();
    }

    String priorityId(String name) {
        return read(get(admin, "/api/meta")).get("priorities").valueStream()
                .filter(p -> p.get("name").asString().equals(name)).findFirst().orElseThrow().get("id").asString();
    }
}
