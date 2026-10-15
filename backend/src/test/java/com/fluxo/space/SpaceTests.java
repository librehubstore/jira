package com.fluxo.space;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance criteria §4.2 and permission matrix §2. */
class SpaceTests extends IntegrationTest {

    @Test
    void newSpaceIsReadyWithThreeColumnBoardAndDefaultRoles() {
        String key = randomKey();
        JsonNode space = createSpace(key, "Paiements");
        assertThat(space.get("statuses")).hasSize(3);
        assertThat(space.get("ticketTypes")).hasSize(5);
        assertThat(space.get("boards")).hasSize(1);
        assertThat(space.get("boards").get(0).get("isDefault").asBoolean()).isTrue();

        JsonNode roles = read(get(admin(), "/api/spaces/" + key + "/roles"));
        assertThat(roles.valueStream().map(r -> r.get("name").asString())).containsExactly("Admin", "Lecteur", "Membre");
    }

    @Test
    void invalidKeysAreRejected() {
        for (String key : List.of("pay", "P", "1AB", "ABCDEFGHIJK")) {
            assertThat(post(admin(), "/api/spaces", Map.of("key", key, "name", "X")))
                    .hasStatus(400).bodyJson().hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("INVALID_KEY"));
        }
        String key = randomKey();
        createSpace(key, "A");
        assertThat(post(admin(), "/api/spaces", Map.of("key", key, "name", "B"))).hasStatus(409);
    }

    @Test
    void onlyGlobalAdminsCreateSpaces() {
        MockHttpSession user = login(createUser("nocreate"), PASSWORD);
        assertThat(post(user, "/api/spaces", Map.of("key", randomKey(), "name", "X"))).hasStatus(403);
    }

    @Test
    void permissionMatrix() {
        String key = randomKey();
        createSpace(key, "Matrice");
        String memberName = createUser("member");
        String viewerName = createUser("viewer");
        String outsiderName = createUser("outsider");
        addMember(key, memberName, "Membre");
        addMember(key, viewerName, "Lecteur");

        MockHttpSession member = login(memberName, PASSWORD);
        MockHttpSession viewer = login(viewerName, PASSWORD);
        MockHttpSession outsider = login(outsiderName, PASSWORD);

        // Outsiders cannot even see that the space exists.
        assertThat(get(outsider, "/api/spaces/" + key)).hasStatus(404);
        assertThat(read(get(outsider, "/api/spaces")).valueStream().map(s -> s.get("key").asString())).doesNotContain(key);

        JsonNode memberView = read(get(member, "/api/spaces/" + key));
        assertThat(memberView.get("myPermissions").valueStream().map(JsonNode::asString))
                .contains("CREATE", "EDIT", "TRANSITION").doesNotContain("ADMINISTER_SPACE", "DELETE", "MANAGE_BOARD");
        assertThat(read(get(viewer, "/api/spaces/" + key)).get("myPermissions").valueStream().map(JsonNode::asString))
                .containsExactly("BROWSE");

        assertThat(patch(member, "/api/spaces/" + key, Map.of("name", "Piraté"))).hasStatus(403);
        assertThat(patch(viewer, "/api/spaces/" + key, Map.of("name", "Piraté"))).hasStatus(403);
    }

    @Test
    void groupMembershipGrantsAccess() {
        String key = randomKey();
        createSpace(key, "Groupe");
        String userName = createUser("ingroup");
        String groupId = idOf(post(admin(), "/api/admin/groups", Map.of("name", unique("grp"))));
        String userId = userIdByName(userName);
        assertThat(put(admin(), "/api/admin/groups/" + groupId + "/members", List.of(userId))).hasStatusOk();
        assertThat(post(admin(), "/api/spaces/" + key + "/members", Map.of("groupId", groupId, "roleId", roleId(key, "Membre"))))
                .hasStatus(201);

        assertThat(get(login(userName, PASSWORD), "/api/spaces/" + key)).hasStatusOk();
    }

    @Test
    void renamedKeyKeepsResolvingOldKey() {
        String oldKey = randomKey(), newKey = randomKey();
        createSpace(oldKey, "Renommage");
        assertThat(patch(admin(), "/api/spaces/" + oldKey, Map.of("key", newKey))).hasStatusOk();
        assertThat(read(get(admin(), "/api/spaces/" + oldKey)).get("key").asString()).isEqualTo(newKey);
        // The old key stays reserved for the redirect.
        assertThat(post(admin(), "/api/spaces", Map.of("key", oldKey, "name", "Autre"))).hasStatus(409);
    }

    @Test
    void archivedSpaceIsReadOnlyAndHiddenFromList() {
        String key = randomKey();
        createSpace(key, "Archive");
        assertThat(patch(admin(), "/api/spaces/" + key, Map.of("archived", true))).hasStatusOk();

        assertThat(read(get(admin(), "/api/spaces")).valueStream().map(s -> s.get("key").asString())).doesNotContain(key);
        assertThat(read(get(admin(), "/api/spaces?includeArchived=true")).valueStream().map(s -> s.get("key").asString())).contains(key);
        assertThat(read(get(admin(), "/api/spaces/" + key)).get("myPermissions").valueStream().map(JsonNode::asString))
                .containsExactlyInAnyOrder("BROWSE", "ADMINISTER_SPACE");
        assertThat(patch(admin(), "/api/spaces/" + key, Map.of("name", "X"))).hasStatus(400);
        assertThat(patch(admin(), "/api/spaces/" + key, Map.of("archived", false))).hasStatusOk();
    }

    @Test
    void deleteRequiresTypingTheKey() {
        String key = randomKey();
        createSpace(key, "Suppression");
        assertThat(delete(admin(), "/api/spaces/" + key + "?confirm=WRONG"))
                .hasStatus(400);
        assertThat(delete(admin(), "/api/spaces/" + key + "?confirm=" + key))
                .hasStatus(204);
        assertThat(get(admin(), "/api/spaces/" + key)).hasStatus(404);
    }
}
