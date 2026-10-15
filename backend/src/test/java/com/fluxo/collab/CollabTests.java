package com.fluxo.collab;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** Acceptance criteria §4.4 (comments), §4.5 (attachments), §4.6 (links), §4.7 (watchers). */
class CollabTests extends IntegrationTest {

    String key;
    MockHttpSession admin;

    @BeforeEach
    void setUp() {
        key = randomKey();
        createSpace(key, "Collab");
        admin = admin();
    }

    @Test
    void mentionMakesMemberWatchAndOutsidersAreIgnored() {
        String alice = createUser("alice");
        String outsider = createUser("out");
        addMember(key, alice, "Membre");
        String t = ticket("A");
        assertThat(post(admin, "/api/tickets/" + t + "/comments", Map.of("body", "Peux-tu regarder @" + alice + " et @" + outsider + " ?")))
                .hasStatus(201);

        JsonNode w = read(get(admin, "/api/tickets/" + t + "/watchers"));
        assertThat(w.get("watchers").valueStream().map(u -> u.get("username").asString()))
                .contains(alice, "admin").doesNotContain(outsider);
    }

    @Test
    void commentEditRulesFollowOwnAndAllPermissions() {
        String bob = createUser("bob");
        addMember(key, bob, "Membre");
        String t = ticket("A");
        String commentId = idOf(post(admin, "/api/tickets/" + t + "/comments", Map.of("body", "Commentaire admin")));
        MockHttpSession bobSession = login(bob, PASSWORD);

        JsonNode asBob = read(get(bobSession, "/api/tickets/" + t + "/comments")).get(0);
        assertThat(asBob.get("canEdit").asBoolean()).isFalse();
        assertThat(patch(bobSession, "/api/comments/" + commentId, Map.of("body", "piraté"))).hasStatus(403);

        String own = idOf(post(bobSession, "/api/tickets/" + t + "/comments", Map.of("body", "Le mien")));
        JsonNode edited = read(patch(bobSession, "/api/comments/" + own, Map.of("body", "Le mien, corrigé")));
        assertThat(edited.get("editedAt").isNull()).isFalse();
        assertThat(delete(bobSession, "/api/comments/" + own)).hasStatus(204);

        JsonNode history = read(get(admin, "/api/tickets/" + t + "/history"));
        assertThat(history.valueStream().map(h -> h.get("field").asString())).contains("comment");
    }

    @Test
    void reactionsToggle() {
        String t = ticket("A");
        String c = idOf(post(admin, "/api/tickets/" + t + "/comments", Map.of("body", "Bravo")));
        post(admin, "/api/comments/" + c + "/reactions", Map.of("emoji", "👍"));
        JsonNode r = read(get(admin, "/api/tickets/" + t + "/comments")).get(0).get("reactions").get(0);
        assertThat(r.get("count").asInt()).isEqualTo(1);
        assertThat(r.get("mine").asBoolean()).isTrue();
        post(admin, "/api/comments/" + c + "/reactions", Map.of("emoji", "👍"));
        assertThat(read(get(admin, "/api/tickets/" + t + "/comments")).get(0).get("reactions")).isEmpty();
        assertThat(post(admin, "/api/comments/" + c + "/reactions", Map.of("emoji", "<script>"))).hasStatus(400);
    }

    @Test
    void imageAttachmentGetsThumbnailAndIsServedInline() throws IOException {
        String t = ticket("A");
        var upload = mvc.post().uri("/api/tickets/" + t + "/attachments").multipart()
                .file(new MockMultipartFile("file", "capture.png", "image/png", png()))
                .session(admin).with(csrf()).exchange();
        assertThat(upload).hasStatus(201);
        JsonNode a = read(upload);
        assertThat(a.get("hasThumbnail").asBoolean()).isTrue();

        var download = mvc.get().uri("/api/attachments/" + a.get("id").asString()).session(admin).exchange();
        assertThat(download).hasStatusOk().hasContentType("image/png");
        assertThat(download.getResponse().getHeader("Content-Disposition")).startsWith("inline");
        assertThat(mvc.get().uri("/api/attachments/" + a.get("id").asString() + "?thumbnail=true").session(admin).exchange())
                .hasStatusOk();
        assertThat(read(get(admin, "/api/tickets/" + t + "/history")).valueStream().map(h -> h.get("field").asString()))
                .contains("attachment");
    }

    @Test
    void dangerousFilesAreRefusedOrDownloaded() {
        String t = ticket("A");
        assertThat(mvc.post().uri("/api/tickets/" + t + "/attachments").multipart()
                .file(new MockMultipartFile("file", "setup.exe", "application/octet-stream", new byte[]{1, 2}))
                .session(admin).with(csrf()).exchange()).hasStatus(400);

        var svg = mvc.post().uri("/api/tickets/" + t + "/attachments").multipart()
                .file(new MockMultipartFile("file", "x.svg", "image/svg+xml", "<svg onload='alert(1)'/>".getBytes()))
                .session(admin).with(csrf()).exchange();
        var download = mvc.get().uri("/api/attachments/" + idOf(svg)).session(admin).exchange();
        assertThat(download.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(download).hasContentType("application/octet-stream");
    }

    @Test
    void outsiderCannotDownloadAttachment() {
        String t = ticket("A");
        var upload = mvc.post().uri("/api/tickets/" + t + "/attachments").multipart()
                .file(new MockMultipartFile("file", "notes.txt", "text/plain", "secret".getBytes()))
                .session(admin).with(csrf()).exchange();
        MockHttpSession outsider = login(createUser("spy"), PASSWORD);
        assertThat(mvc.get().uri("/api/attachments/" + idOf(upload)).session(outsider).exchange()).hasStatus(404);
    }

    @Test
    void linksAreVisibleFromBothSidesAndRemovedTogether() {
        String a = ticket("A"), b = ticket("B");
        String blocks = read(get(admin, "/api/link-types")).valueStream()
                .filter(l -> l.get("name").asString().equals("Blocks")).findFirst().orElseThrow().get("id").asString();
        assertThat(post(admin, "/api/tickets/" + a + "/links", Map.of("linkTypeId", blocks, "ticketKey", b))).hasStatus(201);

        JsonNode fromB = read(get(admin, "/api/tickets/" + b + "/links")).get("links").get(0);
        assertThat(fromB.get("label").asString()).isEqualTo("est bloqué par");
        assertThat(fromB.get("ticket").get("key").asString()).isEqualTo(a);
        assertThat(post(admin, "/api/tickets/" + a + "/links", Map.of("linkTypeId", blocks, "ticketKey", b))).hasStatus(409);

        assertThat(delete(admin, "/api/links/" + fromB.get("id").asString())).hasStatus(204);
        assertThat(read(get(admin, "/api/tickets/" + a + "/links")).get("links")).isEmpty();
    }

    @Test
    void linkToInvisibleTicketIsShownRestricted() {
        String other = randomKey();
        createSpace(other, "Privé");
        String a = ticket("A");
        String hidden = idOf(post(admin, "/api/spaces/" + other + "/tickets", Map.of("summary", "secret")));
        String hiddenKey = read(get(admin, "/api/tickets/" + other + "-1")).get("key").asString();
        String relates = read(get(admin, "/api/link-types")).valueStream()
                .filter(l -> l.get("name").asString().equals("Relates")).findFirst().orElseThrow().get("id").asString();
        post(admin, "/api/tickets/" + a + "/links", Map.of("linkTypeId", relates, "ticketKey", hiddenKey));
        String member = createUser("m");
        addMember(key, member, "Membre");

        JsonNode link = read(get(login(member, PASSWORD), "/api/tickets/" + a + "/links")).get("links").get(0);
        assertThat(link.get("restricted").asBoolean()).isTrue();
        assertThat(link.get("ticket").isNull()).isTrue();
        assertThat(hidden).isNotBlank();
    }

    @Test
    void webLinksAcceptOnlyHttp() {
        String t = ticket("A");
        assertThat(post(admin, "/api/tickets/" + t + "/web-links", Map.of("url", "javascript:alert(1)"))).hasStatus(400);
        assertThat(post(admin, "/api/tickets/" + t + "/web-links", Map.of("url", "https://example.org/doc", "title", "Doc")))
                .hasStatus(201);
    }

    @Test
    void creatorWatchesAndCommenterAutoWatches() {
        String carol = createUser("carol");
        addMember(key, carol, "Membre");
        String t = ticket("A");
        post(login(carol, PASSWORD), "/api/tickets/" + t + "/comments", Map.of("body", "je suis"));
        JsonNode w = read(get(admin, "/api/tickets/" + t + "/watchers"));
        assertThat(w.get("watchers").valueStream().map(u -> u.get("username").asString())).contains("admin", carol);
        assertThat(w.get("watching").asBoolean()).isTrue();
    }

    String ticket(String summary) {
        var r = post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", summary));
        assertThat(r).hasStatus(201);
        return read(r).get("key").asString();
    }

    static byte[] png() throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
