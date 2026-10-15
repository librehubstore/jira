package com.fluxo.notification;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §9: recipients per event, never the author, 2-minute grouping, preferences. */
class NotificationTests extends IntegrationTest {

    String key;
    String owner, alice, bob;
    MockHttpSession ownerS, aliceS, bobS;

    @BeforeEach
    void setUp() {
        key = randomKey();
        createSpace(key, "Notif");
        owner = createUser("owner");
        alice = createUser("alice");
        bob = createUser("bob");
        for (String u : List.of(owner, alice, bob)) addMember(key, u, "Membre");
        ownerS = login(owner, PASSWORD);
        aliceS = login(alice, PASSWORD);
        bobS = login(bob, PASSWORD);
    }

    @Test
    void commentNotifiesWatchersButNotTheAuthor() {
        String t = ticket(ownerS, "A");
        post(aliceS, "/api/tickets/" + t + "/comments", Map.of("body", "Je regarde"));
        JsonNode n = notifications(ownerS).get(0);
        assertThat(n.get("event").asString()).isEqualTo("COMMENTED");
        assertThat(n.get("actor").get("username").asString()).isEqualTo(alice);
        assertThat(n.get("payload").get("excerpt").asString()).isEqualTo("Je regarde");
        assertThat(notifications(aliceS)).isEmpty();
    }

    @Test
    void assignmentNotifiesTheNewAssignee() {
        String t = ticket(ownerS, "A");
        patch(ownerS, "/api/tickets/" + t, Map.of("assigneeId", userIdByName(bob)));
        JsonNode n = notifications(bobS).get(0);
        assertThat(n.get("event").asString()).isEqualTo("ASSIGNED");
        assertThat(n.get("payload").get("ticketKey").asString()).isEqualTo(t);
        assertThat(notifications(ownerS)).isEmpty(); // the author
    }

    @Test
    void mentionReachesTheMentionedUser() {
        String t = ticket(ownerS, "A");
        post(ownerS, "/api/tickets/" + t + "/comments", Map.of("body", "@" + bob + " peux-tu valider ?"));
        assertThat(notifications(bobS).get(0).get("event").asString()).isEqualTo("MENTIONED");
    }

    @Test
    void rapidChangesBySamePersonAreGrouped() {
        String t = ticket(ownerS, "A");
        patch(ownerS, "/api/tickets/" + t, Map.of("assigneeId", userIdByName(alice)));
        post(bobS, "/api/tickets/" + t + "/comments", Map.of("body", "je suis")); // bob watches
        read(post(bobS, "/api/notifications/read-all", Map.of()));
        patch(aliceS, "/api/tickets/" + t, Map.of("summary", "B"));
        patch(aliceS, "/api/tickets/" + t, Map.of("summary", "C"));

        List<JsonNode> updates = notifications(bobS).valueStream().filter(n -> n.get("event").asString().equals("UPDATED")).toList();
        assertThat(updates).hasSize(1);
        assertThat(updates.getFirst().get("payload").get("count").asInt()).isEqualTo(2);
        assertThat(updates.getFirst().get("payload").get("changes")).hasSize(2);
    }

    @Test
    void transitionNotifiesReporterAndAssignee() {
        String t = ticket(ownerS, "A");
        patch(ownerS, "/api/tickets/" + t, Map.of("assigneeId", userIdByName(alice)));
        JsonNode space = read(get(ownerS, "/api/spaces/" + key));
        String done = space.get("statuses").valueStream().filter(s -> s.get("category").asString().equals("DONE"))
                .findFirst().orElseThrow().get("id").asString();
        post(bobS, "/api/tickets/" + t + "/transition", Map.of("statusId", done));
        assertThat(notifications(ownerS).get(0).get("event").asString()).isEqualTo("RESOLVED");
        assertThat(notifications(aliceS).valueStream().map(n -> n.get("event").asString())).contains("RESOLVED");
    }

    @Test
    void mutedEventIsNotNotifiedAndReadStateWorks() {
        assertThat(patch(ownerS, "/api/me", Map.of("preferences", Map.of("notifications", Map.of("COMMENTED", false))))).hasStatusOk();
        String t = ticket(ownerS, "A");
        post(aliceS, "/api/tickets/" + t + "/comments", Map.of("body", "x"));
        assertThat(notifications(ownerS)).isEmpty();

        patch(aliceS, "/api/tickets/" + t, Map.of("summary", "Z"));
        JsonNode page = read(get(ownerS, "/api/notifications"));
        assertThat(page.get("unread").asInt()).isEqualTo(1);
        post(ownerS, "/api/notifications/" + page.get("items").get(0).get("id").asString() + "/read", Map.of());
        assertThat(read(get(ownerS, "/api/notifications/unread-count")).get("unread").asInt()).isZero();
    }

    @Test
    void cannotReadSomeoneElsesNotification() {
        String t = ticket(ownerS, "A");
        post(aliceS, "/api/tickets/" + t + "/comments", Map.of("body", "x"));
        String id = notifications(ownerS).get(0).get("id").asString();
        assertThat(post(bobS, "/api/notifications/" + id + "/read", Map.of())).hasStatus(404);
    }

    String ticket(MockHttpSession s, String summary) {
        var r = post(s, "/api/spaces/" + key + "/tickets", Map.of("summary", summary));
        assertThat(r).hasStatus(201);
        return read(r).get("key").asString();
    }

    JsonNode notifications(MockHttpSession s) {
        return read(get(s, "/api/notifications")).get("items");
    }
}
