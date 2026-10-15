package com.fluxo.board;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §4.15 criterion: 5 tickets in « À faire », 2 moved to « En cours » today → today's CFD shows 5 / 2 / 0. */
class CfdTests extends IntegrationTest {

    @Test
    void cumulativeCountsPerColumn() {
        MockHttpSession admin = admin();
        String key = randomKey();
        JsonNode space = createSpace(key, "CFD");
        String boardId = space.get("boards").get(0).get("id").asString();
        String doing = space.get("statuses").valueStream().filter(s -> s.get("name").asString().equals("En cours")).findFirst().orElseThrow().get("id").asString();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 5; i++) keys.add(read(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "T" + i))).get("key").asString());
        for (int i = 0; i < 2; i++) post(admin, "/api/boards/" + boardId + "/move", Map.of("ticketKey", keys.get(i), "statusId", doing));

        LocalDate today = LocalDate.now(ZoneId.of("Europe/Paris"));
        JsonNode cfd = read(get(admin, "/api/boards/" + boardId + "/cfd?from=" + today.minusDays(2) + "&to=" + today));
        assertThat(cfd.get("columns").valueStream().map(JsonNode::asString)).containsExactly("À faire", "En cours", "Terminé");
        assertThat(cfd.get("days")).hasSize(3);
        assertThat(cfd.get("days").get(0).get("cumulative").valueStream().map(JsonNode::asInt)).containsExactly(0, 0, 0);
        assertThat(cfd.get("days").get(2).get("cumulative").valueStream().map(JsonNode::asInt)).containsExactly(5, 2, 0);

        JsonNode narrowed = read(get(admin, "/api/boards/" + boardId + "/cfd?from=" + today + "&to=" + today + "&fql=key = " + keys.getFirst()));
        assertThat(narrowed.get("days").get(0).get("cumulative").valueStream().map(JsonNode::asInt)).containsExactly(1, 1, 0);
    }
}
