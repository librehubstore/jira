package com.fluxo.realtime;

import com.fluxo.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-time signals reach members of the space only, after commit. */
class RealtimeTests extends IntegrationTest {

    @Autowired
    MockMvc raw;

    @Test
    void membersReceiveTicketSignalsOthersDoNot() throws Exception {
        String key = randomKey();
        createSpace(key, "RT");
        MockHttpSession admin = admin();
        MockHttpSession outsider = login(createUser("rt"), PASSWORD);

        MvcResult mine = raw.perform(MockMvcRequestBuilders.get("/api/events").session(admin)).andReturn();
        MvcResult theirs = raw.perform(MockMvcRequestBuilders.get("/api/events").session(outsider)).andReturn();
        assertThat(mine.getRequest().isAsyncStarted()).isTrue();

        String ticket = read(post(admin, "/api/spaces/" + key + "/tickets", Map.of("summary", "temps réel"))).get("key").asString();
        patch(admin, "/api/tickets/" + ticket, Map.of("summary", "renommé", "version", 0));

        String stream = mine.getResponse().getContentAsString();
        assertThat(stream).contains("event:ticket", "\"type\":\"CREATED\"", "\"type\":\"UPDATED\"", "\"ticketKey\":\"" + ticket + "\"");
        assertThat(theirs.getResponse().getContentAsString()).doesNotContain(ticket);
    }
}
