package com.fluxo.auth;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance criteria §4.1. */
class AuthTests extends IntegrationTest {

    @Test
    void initialAdminIsCreatedFromEnvironment() {
        MockHttpSession s = admin();
        assertThat(get(s, "/api/me")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.admin", v -> v.assertThat().isEqualTo(true));
        assertThat(get(null, "/api/setup")).bodyJson()
                .hasPathSatisfying("$.needsSetup", v -> v.assertThat().isEqualTo(false));
        assertThat(post(new MockHttpSession(), "/api/setup",
                Map.of("username", "x", "displayName", "x", "password", "long-enough-pw"))).hasStatus(409);
    }

    @Test
    void sixthAttemptIsRefusedEvenWithCorrectPassword() {
        String user = createUser("lock");
        for (int i = 0; i < 5; i++) {
            assertThat(post(new MockHttpSession(), "/api/auth/login", Map.of("username", user, "password", "wrong-password")))
                    .hasStatus(401);
        }
        assertThat(post(new MockHttpSession(), "/api/auth/login", Map.of("username", user, "password", PASSWORD)))
                .hasStatus(401).bodyJson().hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("ACCOUNT_LOCKED"));
    }

    @Test
    void adminUnlockRestoresAccess() {
        String user = createUser("unlock");
        for (int i = 0; i < 5; i++) {
            post(new MockHttpSession(), "/api/auth/login", Map.of("username", user, "password", "wrong-password"));
        }
        String id = userId(user);
        assertThat(post(admin(), "/api/admin/users/" + id + "/unlock", Map.of())).hasStatusOk();
        login(user, PASSWORD);
    }

    @Test
    void deactivatedUserCannotLoginAndLosesActiveSession() {
        String user = createUser("deact");
        MockHttpSession userSession = login(user, PASSWORD);
        assertThat(patch(admin(), "/api/admin/users/" + userId(user), Map.of("active", false))).hasStatusOk();

        assertThat(get(userSession, "/api/me")).hasStatus(401);
        assertThat(post(new MockHttpSession(), "/api/auth/login", Map.of("username", user, "password", PASSWORD)))
                .hasStatus(401);
    }

    @Test
    void temporaryPasswordMustBeChangedBeforeUsingTheApi() {
        String user = unique("temp");
        post(admin(), "/api/admin/users", Map.of("username", user, "displayName", user, "temporaryPassword", "temporary-pw-1"));
        MockHttpSession s = login(user, "temporary-pw-1");

        assertThat(get(s, "/api/admin/users")).hasStatus(403);
        assertThat(get(s, "/api/me")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.mustChangePassword", v -> v.assertThat().isEqualTo(true));
        assertThat(post(s, "/api/me/password", Map.of("currentPassword", "temporary-pw-1", "newPassword", "short")))
                .hasStatus(400);
        assertThat(post(s, "/api/me/password", Map.of("currentPassword", "temporary-pw-1", "newPassword", PASSWORD)))
                .hasStatusOk();
    }

    @Test
    void nonAdminCannotUseAdminApi() {
        MockHttpSession s = login(createUser("plain"), PASSWORD);
        assertThat(get(s, "/api/admin/users")).hasStatus(403);
    }

    @Test
    void rememberMeCookieLogsBackInWithoutSession() {
        String user = createUser("remember");
        var r = post(new MockHttpSession(), "/api/auth/login", Map.of("username", user, "password", PASSWORD, "rememberMe", true));
        assertThat(r).hasStatusOk();
        var cookie = r.getResponse().getCookie("fluxo-remember");
        assertThat(cookie).isNotNull();
        assertThat(mvc.get().uri("/api/me").cookie(cookie).exchange()).hasStatusOk();
    }

    @Test
    void anonymousGets401() {
        assertThat(get(null, "/api/me")).hasStatus(401);
    }

    private String userId(String username) {
        var tree = json.readTree(get(admin(), "/api/admin/users").getResponse().getContentAsByteArray());
        for (var n : tree) if (n.get("username").asString().equals(username)) return n.get("id").asString();
        throw new AssertionError("user not found: " + username);
    }
}
