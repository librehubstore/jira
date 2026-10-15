package com.fluxo.field;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §4.14: custom fields enabled per space and type, field configuration, FQL by field name. */
class CustomFieldTests extends IntegrationTest {

    String key;
    JsonNode space;
    MockHttpSession admin;
    String client, env, points;

    @BeforeEach
    void setUp() {
        key = randomKey();
        space = createSpace(key, "Champs");
        admin = admin();
        client = field("Client final " + key, "TEXT", List.of());
        env = field("Environnement " + key, "SELECT", List.of("Recette", "Production"));
        points = field("Points " + key, "NUMBER", List.of());
        assertThat(put(admin, "/api/spaces/" + key + "/fields/enabled", List.of(
                Map.of("fieldId", client, "typeIds", List.of()),
                Map.of("fieldId", env, "typeIds", List.of(type("Bug"))),
                Map.of("fieldId", points, "typeIds", List.of())))).hasStatusOk();
    }

    @Test
    void valuesAreValidatedPerTypeAndRecorded() {
        String bug = ticket(Map.of("summary", "bug", "typeId", type("Bug"), "customFields", Map.of(env, "production", points, "3.50")));
        JsonNode t = read(get(admin, "/api/tickets/" + bug));
        assertThat(t.get("customFields").get(env).asString()).isEqualTo("Production");
        assertThat(t.get("customFields").get(points).asDouble()).isEqualTo(3.5);
        // Not enabled for tasks, unknown option, wrong number.
        assertThat(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "x", "customFields", Map.of(env, "Recette")))).hasStatus(400);
        assertThat(patch(admin, "/api/tickets/" + bug, Map.of("customFields", Map.of(env, "Démo")))).hasStatus(400);
        assertThat(patch(admin, "/api/tickets/" + bug, Map.of("customFields", Map.of(points, "beaucoup")))).hasStatus(400);
        assertThat(patch(admin, "/api/tickets/" + bug, Map.of("customFields", Map.of(client, "ACME")))).hasStatusOk();
        assertThat(read(get(admin, "/api/tickets/" + bug + "/history")).valueStream().map(h -> h.get("field").asString()))
                .contains("Client final " + key);
    }

    @Test
    void configurationRequiresAtCreationAndSetsDefaults() {
        assertThat(put(admin, "/api/spaces/" + key + "/fields/configs", List.of(
                Map.of("field", "cf:" + client, "visible", true, "required", true, "position", 1),
                Map.of("field", "cf:" + points, "visible", true, "required", false, "position", 2, "defaultValue", 1),
                Map.of("field", "dueDate", "visible", false, "required", false, "position", 3)))).hasStatusOk();
        var r = post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "sans client"));
        assertThat(r).hasStatus(400).bodyJson().hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("FIELD_REQUIRED"));
        String k = ticket(Map.of("summary", "avec client", "customFields", Map.of(client, "ACME")));
        assertThat(read(get(admin, "/api/tickets/" + k)).get("customFields").get(points).asInt()).isEqualTo(1);

        JsonNode layout = read(get(admin, "/api/spaces/" + key + "/layout"));
        assertThat(layout.get(0).get("field").asString()).isEqualTo("cf:" + client);
        assertThat(layout.valueStream().filter(l -> l.get("field").asString().equals("dueDate")).findFirst().orElseThrow().get("visible").asBoolean()).isFalse();
        assertThat(put(admin, "/api/spaces/" + key + "/fields/configs", List.of(Map.of("field", "summary", "visible", true, "position", 0)))).hasStatus(400);
    }

    @Test
    void fqlByFieldName() {
        String a = ticket(Map.of("summary", "a", "customFields", Map.of(client, "ACME Corp", points, 5)));
        String b = ticket(Map.of("summary", "b", "customFields", Map.of(client, "Globex", points, 2)));
        ticket(Map.of("summary", "c"));
        assertThat(fql("\"Client final " + key + "\" ~ acme")).containsExactly(a);
        assertThat(fql("\"Points " + key + "\" >= 3")).containsExactly(a);
        assertThat(fql("\"Client final " + key + "\" IS EMPTY")).hasSize(1);
        assertThat(fql("\"Client final " + key + "\" in (Globex, \"ACME Corp\") ORDER BY key")).containsExactly(a, b);
    }

    @Test
    void onlyTheAdminDefinesFields() {
        MockHttpSession member = login(createUser("cf"), PASSWORD);
        assertThat(post(member, "/api/admin/custom-fields", Map.of("name", "x", "type", "TEXT"))).hasStatus(403);
        assertThat(post(admin, "/api/admin/custom-fields", Map.of("name", "Liste vide " + key, "type", "SELECT"))).hasStatus(400);
    }

    // --- helpers ---

    String field(String name, String type, List<String> options) {
        var r = post(admin, "/api/admin/custom-fields", Map.of("name", name, "type", type, "options", options));
        assertThat(r).hasStatus(201);
        return read(r).get("id").asString();
    }

    List<String> fql(String q) {
        var r = get(admin, "/api/search?fql=space = " + key + " AND " + q);
        assertThat(r).hasStatusOk();
        return read(r).get("rows").valueStream().map(x -> x.get("key").asString()).toList();
    }

    String ticket(Map<String, Object> body) {
        var r = post(admin, "/api/spaces/" + key + "/tickets", new HashMap<>(body));
        assertThat(r).as(() -> new String(r.getResponse().getContentAsByteArray())).hasStatus(201);
        return read(r).get("key").asString();
    }

    String type(String name) {
        return space.get("ticketTypes").valueStream().filter(t -> t.get("name").asString().equals(name)).findFirst().orElseThrow().get("id").asString();
    }
}
