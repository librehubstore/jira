package com.fluxo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** Base class: full app on a real PostgreSQL (Testcontainers), shared across test classes. */
@SpringBootTest(properties = {"fluxo.admin.username=admin", "fluxo.admin.password=admin-password",
        "fluxo.storage-dir=target/test-attachments"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    protected static final String ADMIN_PASSWORD = "admin-password";
    protected static final String PASSWORD = "password-123";

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected JsonMapper json;

    protected String toJson(Object o) {
        return json.writeValueAsString(o);
    }

    protected MvcTestResult post(MockHttpSession session, String url, Object body) {
        return mvc.post().uri(url).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)).exchange();
    }

    protected MvcTestResult patch(MockHttpSession session, String url, Object body) {
        return mvc.patch().uri(url).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)).exchange();
    }

    protected MvcTestResult put(MockHttpSession session, String url, Object body) {
        return mvc.put().uri(url).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)).exchange();
    }

    protected MvcTestResult delete(MockHttpSession session, String url) {
        return mvc.delete().uri(url).session(session).with(csrf()).exchange();
    }

    protected MvcTestResult get(MockHttpSession session, String url) {
        return mvc.get().uri(url).session(session != null ? session : new MockHttpSession()).exchange();
    }

    protected MockHttpSession login(String username, String password) {
        MockHttpSession session = new MockHttpSession();
        MvcTestResult r = post(session, "/api/auth/login", Map.of("username", username, "password", password));
        assertThat(r).as(() -> r.getResponse().getStatus() + " " + new String(r.getResponse().getContentAsByteArray())).hasStatusOk();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    protected MockHttpSession admin() {
        return login("admin", ADMIN_PASSWORD);
    }

    protected static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Creates a regular user with a non-temporary password and returns its username. */
    protected String createUser(String prefix) {
        String username = unique(prefix);
        MockHttpSession admin = admin();
        MvcTestResult r = post(admin, "/api/admin/users", Map.of("username", username, "displayName", username,
                "temporaryPassword", "temporary-pw-1"));
        assertThat(r).hasStatus(201);
        MockHttpSession s = login(username, "temporary-pw-1");
        assertThat(post(s, "/api/me/password", Map.of("currentPassword", "temporary-pw-1", "newPassword", PASSWORD)))
                .hasStatusOk();
        return username;
    }

    protected String idOf(MvcTestResult r) {
        return json.readTree(r.getResponse().getContentAsByteArray()).get("id").asString();
    }

    protected JsonNode read(MvcTestResult r) {
        return json.readTree(r.getResponse().getContentAsByteArray());
    }

    protected static String randomKey() {
        StringBuilder sb = new StringBuilder("T");
        for (int i = 0; i < 6; i++) sb.append((char) ('A' + (int) (Math.random() * 26)));
        return sb.toString();
    }

    /** Creates a space as admin and returns its detail. */
    protected JsonNode createSpace(String key, String name) {
        MvcTestResult r = post(admin(), "/api/spaces", Map.of("key", key, "name", name));
        assertThat(r).hasStatus(201);
        return read(r);
    }

    protected void addMember(String key, String username, String role) {
        assertThat(post(admin(), "/api/spaces/" + key + "/members",
                Map.of("userId", userIdByName(username), "roleId", roleId(key, role)))).hasStatus(201);
    }

    protected String roleId(String key, String role) {
        return read(get(admin(), "/api/spaces/" + key + "/roles")).valueStream()
                .filter(r -> r.get("name").asString().equals(role)).findFirst().orElseThrow().get("id").asString();
    }

    protected String userIdByName(String username) {
        return read(get(admin(), "/api/directory/users")).valueStream()
                .filter(u -> u.get("username").asString().equals(username)).findFirst().orElseThrow().get("id").asString();
    }
}
