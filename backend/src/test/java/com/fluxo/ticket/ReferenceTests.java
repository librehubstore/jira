package com.fluxo.ticket;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V1.6.3: global reference data administration. */
class ReferenceTests extends IntegrationTest {

    @Test
    void prioritiesCanBeAddedReorderedAndReplaced() {
        MockHttpSession admin = admin();
        String name = "Urgente " + randomKey();
        assertThat(post(admin, "/api/admin/priorities", Map.of("name", name, "color", "#ff0000"))).hasStatus(201);
        List<JsonNode> list = new ArrayList<>(read(get(admin, "/api/admin/reference")).get("priorities").valueStream().toList());
        JsonNode mine = list.stream().filter(p -> p.get("name").asString().equals(name)).findFirst().orElseThrow();
        List<String> ids = new ArrayList<>(list.stream().map(p -> p.get("id").asString()).toList());
        ids.remove(mine.get("id").asString());
        ids.addFirst(mine.get("id").asString());
        assertThat(put(admin, "/api/admin/priorities/order", ids)).hasStatusOk();
        assertThat(read(get(admin, "/api/admin/reference")).get("priorities").get(0).get("name").asString()).isEqualTo(name);

        String key = randomKey();
        createSpace(key, "Ref");
        String k = read(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "x", "priorityId", mine.get("id").asString()))).get("key").asString();
        String other = ids.get(1);
        assertThat(delete(admin, "/api/admin/priorities/" + mine.get("id").asString())).hasStatus(409);
        assertThat(delete(admin, "/api/admin/priorities/" + mine.get("id").asString() + "?replaceWith=" + other)).hasStatus(204);
        assertThat(read(get(admin, "/api/tickets/" + k)).get("priority").get("id").asString()).isEqualTo(other);
    }

    @Test
    void typesAndSpaceSelection() {
        MockHttpSession admin = admin();
        String name = "Incident " + randomKey();
        assertThat(post(admin, "/api/admin/ticket-types", Map.of("name", name, "icon", "bug", "color", "#dc2626", "hierarchyLevel", 0))).hasStatus(201);
        String id = read(get(admin, "/api/admin/reference")).get("types").valueStream().filter(t -> t.get("name").asString().equals(name))
                .findFirst().orElseThrow().get("id").asString();
        String key = randomKey();
        JsonNode space = createSpace(key, "Types");
        List<String> ids = new ArrayList<>(space.get("ticketTypes").valueStream().map(t -> t.get("id").asString()).toList());
        ids.add(id);
        assertThat(put(admin, "/api/spaces/" + key + "/ticket-types", ids)).hasStatusOk();
        assertThat(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "x", "typeId", id))).hasStatus(201);
        assertThat(delete(admin, "/api/admin/ticket-types/" + id)).hasStatus(409);
        assertThat(get(login(createUser("ref"), PASSWORD), "/api/admin/reference")).hasStatus(403);
    }
}
