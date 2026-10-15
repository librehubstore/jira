package com.fluxo.ticket;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** ADD_FUNCTIONALITIES: collaborators (A.1), archive (A.2), uploaded avatars (A.4). */
class AddedFeaturesTests extends IntegrationTest {

    @Test
    void collaboratorsShowOnTheCardAndFollowTheTicket() {
        MockHttpSession admin = admin();
        String key = randomKey();
        JsonNode space = createSpace(key, "Collab");
        String alice = createUser("alice"), outsider = createUser("dehors");
        addMember(key, alice, "Membre");
        String k = read(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "x", "collaboratorIds", List.of(userIdByName(alice))))).get("key").asString();
        assertThat(read(get(admin, "/api/tickets/" + k)).get("collaborators").get(0).get("username").asString()).isEqualTo(alice);
        assertThat(read(get(admin, "/api/tickets/" + k + "/watchers")).toString()).contains(alice);
        assertThat(patch(admin, "/api/tickets/" + k, Map.of("collaboratorIds", List.of(userIdByName(outsider))))).hasStatus(400);

        String boardId = space.get("boards").get(0).get("id").asString();
        JsonNode card = read(get(admin, "/api/boards/" + boardId + "/cards")).get("cards").get(0);
        assertThat(card.get("collaborators").get(0).get("username").asString()).isEqualTo(alice);
        assertThat(card.get("reporter").get("username").asString()).isEqualTo("admin");
        assertThat(read(get(admin, "/api/search?fql=collaborator = " + alice)).get("rows").get(0).get("key").asString()).isEqualTo(k);
    }

    @Test
    void archivedTicketsLeaveBoardAndDefaultSearch() {
        MockHttpSession admin = admin();
        String key = randomKey();
        JsonNode space = createSpace(key, "Archive");
        String k = read(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "vieux"))).get("key").asString();
        assertThat(patch(admin, "/api/tickets/" + k, Map.of("archived", true))).hasStatusOk();
        String boardId = space.get("boards").get(0).get("id").asString();
        assertThat(read(get(admin, "/api/boards/" + boardId + "/cards")).get("cards")).isEmpty();
        assertThat(read(get(admin, "/api/search?spaces=" + key)).get("total").asInt()).isZero();
        assertThat(read(get(admin, "/api/search?fql=space = " + key + " AND archived = true")).get("total").asInt()).isEqualTo(1);
        assertThat(read(get(admin, "/api/tickets/" + k)).get("archived").asBoolean()).isTrue();
    }

    @Test
    void avatarIsSquaredResizedAndReferenced() throws Exception {
        String bob = createUser("bob");
        MockHttpSession s = login(bob, PASSWORD);
        BufferedImage img = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        var r = mvc.post().uri("/api/me/avatar").multipart().file(new MockMultipartFile("file", "moi.png", "image/png", png.toByteArray()))
                .session(s).with(csrf()).exchange();
        assertThat(r).hasStatusOk();
        String url = read(r).get("avatar").asString();
        assertThat(read(get(s, "/api/me")).get("avatar").asString()).isEqualTo(url);
        var image = get(s, url);
        assertThat(image).hasStatusOk();
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(image.getResponse().getContentAsByteArray()));
        assertThat(stored.getWidth()).isEqualTo(128);
        assertThat(stored.getHeight()).isEqualTo(128);
        var bad = mvc.post().uri("/api/me/avatar").multipart().file(new MockMultipartFile("file", "x.png", "image/png", "pas une image".getBytes()))
                .session(s).with(csrf()).exchange();
        assertThat(bad).hasStatus(400);
    }
}
