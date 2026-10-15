package com.fluxo.auth;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §8: personal access tokens (scope, revocation) and the OpenAPI description. */
class ApiTokenTests extends IntegrationTest {

    MvcTestResult bearerGet(String token, String url) {
        return mvc.get().uri(url).header("Authorization", "Bearer " + token).exchange();
    }

    MvcTestResult bearerPost(String token, String url, Object body) {
        return mvc.post().uri(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(toJson(body)).exchange();
    }

    @Test
    void tokensAuthenticateWithinTheirScopeUntilRevoked() {
        String user = createUser("api");
        MockHttpSession s = login(user, PASSWORD);
        var r = post(s, "/api/me/tokens", Map.of("name", "CI", "scope", "READ"));
        assertThat(r).hasStatus(201);
        String read = read(r).get("secret").asString();
        String id = read(r).get("token").get("id").asString();
        assertThat(read).startsWith("flx_");

        assertThat(bearerGet(read, "/api/me")).hasStatusOk().bodyJson().hasPathSatisfying("$.username", v -> v.assertThat().isEqualTo(user));
        String key = randomKey();
        createSpace(key, "API");
        addMember(key, user, "Membre");
        assertThat(bearerPost(read, "/api/spaces/" + key + "/tickets", Map.of("summary", "x"))).hasStatus(403);
        assertThat(bearerGet(read, "/api/me/tokens")).hasStatus(403);
        assertThat(read(get(s, "/api/me/tokens")).get(0).get("lastUsedAt").isNull()).isFalse();

        String write = read(post(s, "/api/me/tokens", Map.of("name", "Script", "scope", "WRITE"))).get("secret").asString();
        assertThat(bearerPost(write, "/api/spaces/" + key + "/tickets", Map.of("summary", "via API"))).hasStatus(201); // no CSRF token needed

        assertThat(delete(s, "/api/me/tokens/" + id)).hasStatus(204);
        assertThat(bearerGet(read, "/api/me")).hasStatus(401);
        assertThat(bearerGet("flx_nope", "/api/me")).hasStatus(401);
    }

    @Test
    void openApiDescribesTheApi() throws Exception {
        var r = get(admin(), "/api/openapi.json");
        assertThat(r).hasStatusOk();
        assertThat(r.getResponse().getContentAsString()).contains("\"openapi\"", "/api/tickets/{key}");
        assertThat(get(null, "/api/openapi.json")).hasStatus(401);
    }
}
