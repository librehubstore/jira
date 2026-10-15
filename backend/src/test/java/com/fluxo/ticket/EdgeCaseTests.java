package com.fluxo.ticket;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** Refusals and edge cases of reference data, releases, tickets and attachments. */
class EdgeCaseTests extends IntegrationTest {

    @Test
    void referenceDataRefusals() {
        MockHttpSession admin = admin();
        JsonNode ref = read(get(admin, "/api/admin/reference"));
        String existing = ref.get("priorities").get(0).get("name").asString();
        assertThat(post(admin, "/api/admin/priorities", Map.of("name", existing, "color", "#000000"))).hasStatus(409);
        assertThat(patch(admin, "/api/admin/priorities/" + UUID.randomUUID(), Map.of("name", "x"))).hasStatus(404);
        String defaultPriority = ref.get("priorities").valueStream().filter(p -> p.get("isDefault").asBoolean()).findFirst().orElseThrow().get("id").asString();
        assertThat(delete(admin, "/api/admin/priorities/" + defaultPriority)).hasStatus(400);
        String blocks = ref.get("linkTypes").valueStream().filter(l -> l.get("name").asString().equals("Cloners")).findFirst().orElseThrow().get("id").asString();
        assertThat(delete(admin, "/api/admin/link-types/" + blocks)).hasStatus(400);

        String key = randomKey();
        JsonNode space = createSpace(key, "Edge");
        post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "tâche"));
        List<String> withoutTask = space.get("ticketTypes").valueStream().filter(t -> !t.get("name").asString().equals("Tâche"))
                .map(t -> t.get("id").asString()).toList();
        assertThat(put(admin, "/api/spaces/" + key + "/ticket-types", withoutTask)).hasStatus(409);
    }

    @Test
    void releaseRefusalsAndExistingVersion() {
        MockHttpSession admin = admin();
        String key = randomKey();
        JsonNode space = createSpace(key, "Release");
        String boardId = space.get("boards").get(0).get("id").asString();
        String v = read(post(admin, "/api/spaces/" + key + "/versions", Map.of("name", "2.0"))).get("id").asString();
        assertThat(post(admin, "/api/boards/" + boardId + "/release", Map.of("name", "2.0"))).hasStatus(409);
        assertThat(post(admin, "/api/boards/" + boardId + "/release", Map.of("versionId", UUID.randomUUID().toString()))).hasStatus(400);
        assertThat(post(admin, "/api/boards/" + boardId + "/release", Map.of("versionId", v))).hasStatusOk();
    }

    @Test
    void typeCannotBeClearedAndAttachmentsWithoutContentType() throws Exception {
        MockHttpSession admin = admin();
        String key = randomKey();
        createSpace(key, "Types");
        String k = read(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "x"))).get("key").asString();
        Map<String, Object> body = new HashMap<>();
        body.put("typeId", null);
        assertThat(patch(admin, "/api/tickets/" + k, body)).hasStatus(400);

        var file = new MockMultipartFile("file", "notes.txt", null, "bonjour".getBytes());
        var r = mvc.post().uri("/api/tickets/" + k + "/attachments").multipart().file(file).session(admin).with(csrf()).exchange();
        assertThat(r).hasStatus(201);
        assertThat(read(r).get("mime").asString()).isEqualTo("application/octet-stream");
    }

    @Test
    void estimatesWithAndWithoutUnit() {
        assertThat(ImportService.estimate("3 pts", "h", "POINTS")).isEqualByComparingTo("3");
        assertThat(ImportService.estimate("4", "h", "HOURS")).isEqualByComparingTo("4");
    }
}
