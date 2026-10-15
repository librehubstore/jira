package com.fluxo.bulk;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V1.5: bulk changes in the background (§4.12), cloning with options and templates (§4.3). */
class ProductivityTests extends IntegrationTest {

    String key;
    JsonNode space;
    MockHttpSession admin;

    @BeforeEach
    void setUp() {
        key = randomKey();
        space = createSpace(key, "Prod");
        admin = admin();
    }

    @Test
    void bulkEditReportsRefusalsWithoutStoppingTheRest() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 5; i++) keys.add(ticket("T" + i, Map.of("labels", List.of("old"))));
        String other = randomKey();
        createSpace(other, "Autre");
        String member = createUser("bulk");
        addMember(key, member, "Membre");
        keys.add(read(post(admin, "/api/spaces/" + other + "/tickets", Map.of("summary", "ailleurs"))).get("key").asString());

        MockHttpSession s = login(member, PASSWORD);
        var r = post(s, "/api/bulk", Map.of("operation", "EDIT", "keys", keys,
                "params", Map.of("labels", Map.of("mode", "ADD", "values", List.of("lot")), "priorityId", priority("Haute"))));
        assertThat(r).hasStatus(202);
        JsonNode job = await(s, read(r).get("id").asString());
        assertThat(job.get("done").asInt()).isEqualTo(6);
        assertThat(job.get("failed").asInt()).isEqualTo(1); // not a member of the other space
        assertThat(job.get("report").get(0).get("key").asString()).isEqualTo(keys.getLast());
        JsonNode t = read(get(admin, "/api/tickets/" + keys.getFirst()));
        assertThat(t.get("labels").valueStream().map(JsonNode::asString)).containsExactlyInAnyOrder("old", "lot");
        assertThat(t.get("priority").get("name").asString()).isEqualTo("Haute");
    }

    @Test
    void bulkTransitionAndDelete() {
        String a = ticket("A", Map.of()), b = ticket("B", Map.of());
        JsonNode job = await(admin, read(post(admin, "/api/bulk", Map.of("operation", "TRANSITION", "keys", List.of(a, b),
                "params", Map.of("statusName", "Terminé")))).get("id").asString());
        assertThat(job.get("failed").asInt()).isZero();
        assertThat(read(get(admin, "/api/tickets/" + b)).get("status").get("name").asString()).isEqualTo("Terminé");
        await(admin, read(post(admin, "/api/bulk", Map.of("operation", "DELETE", "keys", List.of(a, b)))).get("id").asString());
        assertThat(get(admin, "/api/tickets/" + a)).hasStatus(404);
    }

    @Test
    void bulkLimitsAndOwnership() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 1001; i++) many.add(key + "-" + i);
        assertThat(post(admin, "/api/bulk", Map.of("operation", "WATCH", "keys", many))).hasStatus(400);
        String id = read(post(admin, "/api/bulk", Map.of("operation", "WATCH", "keys", List.of(ticket("A", Map.of()))))).get("id").asString();
        assertThat(get(login(createUser("nosy"), PASSWORD), "/api/bulk/" + id)).hasStatus(404);
    }

    @Test
    void cloneWithOptions() {
        String src = ticket("Corriger le login", Map.of("description", "- [ ] étape 1\n- [x] étape 2", "labels", List.of("auth")));
        String sub = ticket("sous", Map.of("typeId", type("Sous-tâche"), "parent", src));
        post(admin, "/api/tickets/" + src + "/comments", Map.of("body", "premier commentaire"));
        var r = post(admin, "/api/tickets/" + src + "/clone", Map.of("comments", true, "subtasks", true, "links", true, "attachments", true));
        assertThat(r).hasStatus(201);
        JsonNode copy = read(r);
        assertThat(copy.get("summary").asString()).isEqualTo("COPIE – Corriger le login");
        assertThat(copy.get("description").asString()).isEqualTo("- [ ] étape 1\n- [x] étape 2"); // rich content kept as is
        assertThat(copy.get("children").valueStream().map(c -> c.get("summary").asString())).containsExactly("sous");
        String copyKey = copy.get("key").asString();
        assertThat(read(get(admin, "/api/tickets/" + copyKey + "/comments")).valueStream().map(c -> c.get("body").asString()))
                .containsExactly("premier commentaire");
        assertThat(read(get(admin, "/api/tickets/" + copyKey + "/links")).get("links").valueStream().map(l -> l.get("label").asString() + " " + l.get("ticket").get("key").asString()))
                .contains("clone " + src);
        assertThat(sub).isNotEqualTo(copyKey);
    }

    @Test
    void templatesCreateTicketsWithSubtasks() {
        var r = post(admin, "/api/spaces/" + key + "/templates", Map.of("name", "Mise en production", "typeId", type("Tâche"),
                "values", Map.of("description", "Checklist MEP", "labels", List.of("mep")), "subtasks", List.of("Sauvegarde", "Déploiement")));
        assertThat(r).hasStatus(201);
        String id = read(r).get("id").asString();
        assertThat(read(get(admin, "/api/spaces/" + key + "/templates")).size()).isEqualTo(1);
        var created = post(admin, "/api/templates/" + id + "/tickets", Map.of("summary", "MEP v2"));
        assertThat(created).hasStatus(201);
        JsonNode t = read(created);
        assertThat(t.get("description").asString()).isEqualTo("Checklist MEP");
        assertThat(t.get("labels").valueStream().map(JsonNode::asString)).containsExactly("mep");
        assertThat(t.get("children").valueStream().map(c -> c.get("summary").asString())).containsExactly("Sauvegarde", "Déploiement");
        String member = createUser("tpl");
        addMember(key, member, "Membre");
        assertThat(post(login(member, PASSWORD), "/api/spaces/" + key + "/templates", Map.of("name", "x"))).hasStatus(403);
    }

    // --- helpers ---

    JsonNode await(MockHttpSession s, String id) {
        Instant end = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(end)) {
            JsonNode job = read(get(s, "/api/bulk/" + id));
            if (job.get("status").asString().equals("DONE")) return job;
            try { Thread.sleep(50); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        }
        throw new AssertionError("bulk job " + id + " did not finish");
    }

    String ticket(String summary, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("summary", summary);
        var r = post(admin, "/api/spaces/" + key + "/tickets", body);
        assertThat(r).hasStatus(201);
        return read(r).get("key").asString();
    }

    String type(String name) {
        return space.get("ticketTypes").valueStream().filter(t -> t.get("name").asString().equals(name)).findFirst().orElseThrow().get("id").asString();
    }

    String priority(String name) {
        return read(get(admin, "/api/meta")).get("priorities").valueStream().filter(p -> p.get("name").asString().equals(name))
                .findFirst().orElseThrow().get("id").asString();
    }
}
