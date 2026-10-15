package com.fluxo.notification;

import com.fluxo.IntegrationTest;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** V1.7: comments restricted to a role (§4.4), attachment rename (§4.5), email notifications grouped per ticket (§9). */
class CollaborationV1Tests extends IntegrationTest {

    @MockitoBean
    JavaMailSender mail;

    @Autowired
    EmailService emails;

    @Autowired
    JdbcClient jdbc;

    String key;
    MockHttpSession admin;

    @BeforeEach
    void setUp() {
        key = randomKey();
        createSpace(key, "Collab V1");
        admin = admin();
        when(mail.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void restrictedCommentsAreHiddenFromOtherRoles() {
        String member = createUser("membre"), viewer = createUser("lecteur");
        addMember(key, member, "Membre");
        addMember(key, viewer, "Lecteur");
        String k = ticket();
        String memberRole = roleId(key, "Membre");
        MockHttpSession m = login(member, PASSWORD);
        assertThat(post(m, "/api/tickets/" + k + "/comments", Map.of("body", "interne", "visibility", memberRole))).hasStatus(201);
        assertThat(post(m, "/api/tickets/" + k + "/comments", Map.of("body", "public"))).hasStatus(201);
        assertThat(post(m, "/api/tickets/" + k + "/comments", Map.of("body", "x", "visibility", roleId(key, "Admin")))).hasStatus(403);

        MockHttpSession v = login(viewer, PASSWORD);
        assertThat(read(get(v, "/api/tickets/" + k + "/comments")).valueStream().map(c -> c.get("body").asString())).containsExactly("public");
        assertThat(read(get(m, "/api/tickets/" + k + "/comments")).size()).isEqualTo(2);
        assertThat(read(get(v, "/api/search?fql=space = " + key + " AND comment ~ interne")).get("total").asInt()).isZero();

        // Space default: new comments restricted unless "" is sent.
        assertThat(patch(admin, "/api/spaces/" + key, Map.of("commentVisibilityRoleId", memberRole))).hasStatusOk();
        post(m, "/api/tickets/" + k + "/comments", Map.of("body", "par défaut"));
        assertThat(read(get(v, "/api/tickets/" + k + "/comments")).size()).isEqualTo(1);
    }

    @Test
    void attachmentsCanBeRenamed() throws Exception {
        String k = ticket();
        var file = new MockMultipartFile("file", "capture.png", "image/png", new byte[]{1, 2, 3});
        var r = mvc.post().uri("/api/tickets/" + k + "/attachments").multipart().file(file).session(admin).with(csrf()).exchange();
        String id = read(r).get("id").asString();
        assertThat(patch(admin, "/api/attachments/" + id, Map.of("filename", "écran-paiement.png"))).hasStatusOk();
        assertThat(read(get(admin, "/api/tickets/" + k + "/attachments")).get(0).get("filename").asString()).isEqualTo("écran-paiement.png");
        assertThat(patch(admin, "/api/attachments/" + id, Map.of("filename", "virus.exe"))).hasStatus(400);
    }

    @Test
    void emailsAreGroupedPerTicketAndThreaded() throws Exception {
        String watcher = createUser("mail");
        addMember(key, watcher, "Membre");
        jdbc.sql("update users set email = ? where username = ?").params(watcher + "@example.test", watcher).update();
        String k = ticket();
        post(admin, "/api/tickets/" + k + "/watchers", Map.of("userId", userIdByName(watcher)));
        patch(admin, "/api/tickets/" + k, Map.of("summary", "Nouveau résumé"));
        post(admin, "/api/tickets/" + k + "/comments", Map.of("body", "Voici le correctif"));

        assertThat(emails.flush(Instant.now().minusSeconds(120))).isZero(); // still inside the 2-minute window
        assertThat(emails.flush(Instant.now().plusSeconds(1))).isEqualTo(1);
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail, times(1)).send(sent.capture());
        MimeMessage msg = sent.getValue();
        assertThat(msg.getSubject()).isEqualTo("[" + k + "] Nouveau résumé");
        assertThat(msg.getHeader("References")[0]).startsWith("<ticket-");
        assertThat((String) msg.getContent()).contains("Voici le correctif", "/browse/" + k);

        // Opting out of an event by email keeps it in-app.
        patch(login(watcher, PASSWORD), "/api/me", Map.of("preferences", Map.of("emailNotifications", Map.of("COMMENTED", false))));
        post(admin, "/api/tickets/" + k + "/comments", Map.of("body", "encore"));
        assertThat(emails.flush(Instant.now().plusSeconds(1))).isZero();
        verify(mail, times(1)).send(any(MimeMessage.class));
    }

    String ticket() {
        var r = post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "Ticket"));
        assertThat(r).hasStatus(201);
        JsonNode t = read(r);
        return t.get("key").asString();
    }
}
