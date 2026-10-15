package com.fluxo.webhook;

import com.fluxo.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §8: signed webhooks, retries with backoff, delivery log. */
@TestPropertySource(properties = "fluxo.webhooks.backoff-ms=20,20")
class WebhookTests extends IntegrationTest {

    record Received(String event, String signature, String body) {}

    HttpServer server;
    final List<Received> received = new CopyOnWriteArrayList<>();
    final AtomicInteger failuresLeft = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes());
            int status = failuresLeft.getAndDecrement() > 0 ? 500 : 204;
            if (status == 204) received.add(new Received(ex.getRequestHeaders().getFirst("X-Fluxo-Event"), ex.getRequestHeaders().getFirst("X-Fluxo-Signature"), body));
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void ticketEventsAreSignedRetriedAndLogged() throws Exception {
        MockHttpSession admin = admin();
        String key = randomKey();
        createSpace(key, "Hooks");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        var r = post(admin, "/api/admin/webhooks", Map.of("url", url, "events", List.of("ticket.created", "comment.created"), "spaceKey", key));
        assertThat(r).hasStatus(201);
        JsonNode hook = read(r);
        failuresLeft.set(1); // the first attempt fails, the retry succeeds

        String ticket = read(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "Webhook"))).get("key").asString();
        await(() -> received.size() == 1);
        Received first = received.getFirst();
        assertThat(first.event()).isEqualTo("ticket.created");
        assertThat(first.body()).contains("\"key\":\"" + ticket + "\"");
        assertThat(first.signature()).isEqualTo("sha256=" + WebhookService.hmac(hook.get("secret").asString(), first.body().getBytes()));

        patch(admin, "/api/tickets/" + ticket, Map.of("summary", "renommé")); // ticket.updated: not subscribed
        post(admin, "/api/tickets/" + ticket + "/comments", Map.of("body", "bonjour"));
        await(() -> received.size() == 2);
        assertThat(received.get(1).event()).isEqualTo("comment.created");

        String id = hook.get("id").asString();
        await(() -> read(get(admin, "/api/admin/webhooks/" + id + "/deliveries")).size() == 2);
        JsonNode deliveries = read(get(admin, "/api/admin/webhooks/" + id + "/deliveries"));
        assertThat(deliveries.valueStream().map(d -> d.get("attempts").asInt())).contains(2);
        assertThat(get(login(createUser("hook"), PASSWORD), "/api/admin/webhooks")).hasStatus(403);
    }

    static void await(java.util.function.BooleanSupplier ok) throws InterruptedException {
        Instant end = Instant.now().plus(Duration.ofSeconds(10));
        while (!ok.getAsBoolean()) {
            if (Instant.now().isAfter(end)) throw new AssertionError("timeout");
            Thread.sleep(30);
        }
    }
}
