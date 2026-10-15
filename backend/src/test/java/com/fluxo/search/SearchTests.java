package com.fluxo.search;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance criteria §4.11 (structured search, MVP). */
class SearchTests extends IntegrationTest {

    String key;
    MockHttpSession admin;

    @BeforeEach
    void setUp() {
        key = randomKey();
        createSpace(key, "Recherche");
        admin = admin();
    }

    @Test
    void textSearchCoversSummaryDescriptionCommentsWithPrefixes() {
        String a = ticket(Map.of("summary", "Paiement refusé par la banque"));
        String b = ticket(Map.of("summary", "Autre", "description", "Le webhook renvoie une erreur"));
        String c = ticket(Map.of("summary", "Encore autre"));
        post(admin, "/api/tickets/" + c + "/comments", Map.of("body", "Vu avec la compta : rapprochement manquant"));

        assertThat(keys("q=paiem&spaces=" + key)).containsExactly(a);
        assertThat(keys("q=webhook&spaces=" + key)).containsExactly(b);
        assertThat(keys("q=rapprochement&spaces=" + key)).containsExactly(c);
        assertThat(keys("q=banque+paiement&spaces=" + key)).containsExactly(a);
    }

    @Test
    void createdTicketIsFoundImmediately() {
        String k = ticket(Map.of("summary", "Zorglub immédiat"));
        assertThat(keys("q=zorglub")).contains(k);
    }

    @Test
    void ticketsOfInvisibleSpacesNeverShowUp() {
        ticket(Map.of("summary", "Confidentiel salaires"));
        MockHttpSession outsider = login(createUser("nosy"), PASSWORD);
        JsonNode r = read(get(outsider, "/api/search?q=salaires"));
        assertThat(r.get("total").asInt()).isZero();
        assertThat(read(get(outsider, "/api/search")).get("total").asInt()).isZero();
        assertThat(read(get(outsider, "/api/search/quick?q=" + key + "-1")).get("exactKey").isNull()).isTrue();
    }

    @Test
    void filtersCombineAndSortWorks() {
        String me = userIdByName("admin");
        String high = read(get(admin, "/api/meta")).get("priorities").valueStream().filter(p -> p.get("name").asString().equals("Haute"))
                .findFirst().orElseThrow().get("id").asString();
        String a = ticket(Map.of("summary", "A", "assigneeId", me, "priorityId", high, "labels", List.of("front")));
        String b = ticket(Map.of("summary", "B", "assigneeId", me));
        String c = ticket(Map.of("summary", "C", "labels", List.of("front")));

        assertThat(keys("spaces=" + key + "&assignees=me")).containsExactlyInAnyOrder(a, b);
        assertThat(keys("spaces=" + key + "&assignees=me&labels=FRONT")).containsExactly(a);
        assertThat(keys("spaces=" + key + "&assignees=none")).containsExactly(c);
        assertThat(keys("spaces=" + key + "&statusCategories=TODO&sort=priority,desc")).first().isEqualTo(a);
        assertThat(keys("spaces=" + key + "&sort=key,desc")).containsExactly(c, b, a);
        assertThat(get(admin, "/api/search?sort=password")).hasStatus(400);
    }

    @Test
    void quickSearchOpensExactKey() {
        String k = ticket(Map.of("summary", "Exact"));
        assertThat(read(get(admin, "/api/search/quick?q=" + k.toLowerCase())).get("exactKey").asString()).isEqualTo(k);
    }

    @Test
    void listShowsLastComment() {
        String k = ticket(Map.of("summary", "Commenté"));
        post(admin, "/api/tickets/" + k + "/comments", Map.of("body", "premier"));
        post(admin, "/api/tickets/" + k + "/comments", Map.of("body", "dernier mot"));
        JsonNode row = read(get(admin, "/api/search?spaces=" + key)).get("rows").get(0);
        assertThat(row.get("lastComment").get("excerpt").asString()).isEqualTo("dernier mot");
    }

    @Test
    void csvExportEscapesAndNeutralisesFormulas() {
        ticket(Map.of("summary", "=HYPERLINK(\"evil\"); oops"));
        var r = mvc.get().uri("/api/search/export.csv?spaces=" + key).session(admin).exchange();
        assertThat(r).hasStatusOk();
        String csv = new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Clé;Type;Résumé");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"evil\"\"); oops\"");
    }

    // --- helpers ---

    String ticket(Map<String, Object> body) {
        var r = post(admin, "/api/spaces/" + key + "/tickets", new java.util.HashMap<>(body));
        assertThat(r).hasStatus(201);
        return read(r).get("key").asString();
    }

    List<String> keys(String query) {
        var r = get(admin, "/api/search?" + query);
        assertThat(r).hasStatusOk();
        return read(r).get("rows").valueStream().map(n -> n.get("key").asString()).toList();
    }
}
