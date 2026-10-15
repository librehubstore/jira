package com.fluxo.ticket;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** Spec §8: CSV import with preview, simulation and explicit estimate units; JSON export of a ticket. */
class ImportTests extends IntegrationTest {

    static final String CSV = """
            ﻿id;Résumé;Type;Estimation;Parent;Labels
            E1;Refonte paiement;Epic;;;
            T1;"Webhook ""Stripe""\";Tâche;2h;E1;back paiement
            T2;"Ligne
            multiligne";Tâche;30m;E1;
            T3;Sans unité connue;Tâche;3pts;;
            """;

    MvcTestResult upload(MockHttpSession s, String url, String csv, String options) {
        var file = new MockMultipartFile("file", "import.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        var req = mvc.post().uri(url).multipart().file(file).session(s).with(csrf());
        if (options != null) req = req.param("options", options);
        return req.exchange();
    }

    @Test
    void parsesQuotesAndLineBreaks() {
        List<List<String>> rows = ImportService.parse(CSV);
        assertThat(rows).hasSize(5);
        assertThat(rows.get(2).get(1)).isEqualTo("Webhook \"Stripe\"");
        assertThat(rows.get(3).get(1)).isEqualTo("Ligne\nmultiligne");
        assertThat(ImportService.estimate("90m", null, "HOURS")).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThatThrownBy(() -> ImportService.estimate("2h", null, "POINTS")).hasMessageContaining("points");
        assertThatThrownBy(() -> ImportService.estimate("2", null, "HOURS")).hasMessageContaining("Unité");
    }

    @Test
    void previewSimulateThenImport() {
        MockHttpSession admin = admin();
        String key = randomKey();
        createSpace(key, "Import");
        patch(admin, "/api/spaces/" + key, Map.of("estimateUnit", "HOURS"));

        JsonNode preview = read(upload(admin, "/api/spaces/" + key + "/import/preview", CSV, null));
        assertThat(preview.get("headers").valueStream().map(JsonNode::asString)).containsExactly("id", "Résumé", "Type", "Estimation", "Parent", "Labels");
        assertThat(preview.get("total").asInt()).isEqualTo(4);

        String options = toJson(Map.of("mapping", Map.of("0", "id", "1", "summary", "2", "type", "3", "estimate", "4", "parent", "5", "labels"),
                "estimateUnit", "h", "dryRun", true));
        JsonNode dry = read(upload(admin, "/api/spaces/" + key + "/import", CSV, options));
        assertThat(dry.get("errors").valueStream().map(e -> e.get("line").asInt() + " " + e.get("message").asString()))
                .singleElement().asString().startsWith("5 ").contains("heures"); // T3 in points (line = CSV record)
        assertThat(read(get(admin, "/api/search?spaces=" + key)).get("total").asInt()).isZero(); // nothing written

        JsonNode real = read(upload(admin, "/api/spaces/" + key + "/import", CSV, options.replace("true", "false")));
        assertThat(real.get("created")).hasSize(3);
        JsonNode t1 = read(get(admin, "/api/tickets/" + real.get("created").get(1).asString()));
        assertThat(t1.get("estimate").decimalValue()).isEqualByComparingTo("2");
        assertThat(t1.get("parent").get("key").asString()).isEqualTo(real.get("created").get(0).asString());
        assertThat(t1.get("labels").valueStream().map(JsonNode::asString)).containsExactlyInAnyOrder("back", "paiement");
        assertThat(read(get(admin, "/api/tickets/" + real.get("created").get(2).asString())).get("estimate").decimalValue()).isEqualByComparingTo("0.5");

        var export = get(admin, "/api/tickets/" + real.get("created").get(1).asString() + "/export.json");
        assertThat(export).hasStatusOk();
        assertThat(read(export).get("ticket").get("summary").asString()).isEqualTo("Webhook \"Stripe\"");
        assertThat(read(export).get("history").size()).isPositive();
    }
}
