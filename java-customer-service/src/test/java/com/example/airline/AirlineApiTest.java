package com.example.airline;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.ai.deepseek.api-key=local-test-placeholder")
@AutoConfigureMockMvc
class AirlineApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean ModelGateway model;

    private Browser browser() throws Exception {
        var result = mvc.perform(get("/api/session")).andExpect(status().isOk()).andReturn();
        return new Browser((MockHttpSession) result.getRequest().getSession(),
                Json.read(result.getResponse().getContentAsString()).path("csrfToken").asString());
    }

    private String create(Browser browser) throws Exception {
        var result = mvc.perform(post("/api/threads").session(browser.session)
                        .header("X-CSRF-Token", browser.csrf).contentType("application/json")
                        .content("{\"scenario\":\"on_time\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agents.length()").value(6)).andReturn();
        return Json.read(result.getResponse().getContentAsString()).path("threadId").asString();
    }

    @Test
    void sessionsCsrfAndOwnershipProtectAllResources() throws Exception {
        mvc.perform(post("/api/threads").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        Browser owner = browser();
        mvc.perform(post("/api/threads").session(owner.session).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        String id = create(owner);
        Browser stranger = browser();
        for (String suffix : new String[]{"", "/seats", "/events"}) {
            mvc.perform(get("/api/threads/" + id + suffix).session(stranger.session))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/threads/" + id + "/operations/confirm").session(stranger.session)
                        .header("X-CSRF-Token", stranger.csrf).contentType("application/json")
                        .content("{\"operationId\":\"unknown\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/threads/does-not-exist").session(owner.session)).andExpect(status().isNotFound());
    }

    @Test
    void seatActionConfirmationAndReplayAreEndToEnd() throws Exception {
        Browser browser = browser();
        String id = create(browser);
        var map = mvc.perform(get("/api/threads/" + id + "/seats").session(browser.session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seats.length()").value(136)).andReturn();
        long version = Json.read(map.getResponse().getContentAsString()).path("version").asLong();
        var selection = mvc.perform(post("/api/threads/" + id + "/seats").session(browser.session)
                        .header("X-CSRF-Token", browser.csrf).contentType("application/json")
                        .content("{\"seatNumber\":\"15C\",\"version\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_CONFIRMATION")).andReturn();
        String operationId = Json.read(selection.getResponse().getContentAsString()).path("operationId").asString();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/threads/" + id + "/operations/confirm").session(browser.session)
                            .header("X-CSRF-Token", browser.csrf).contentType("application/json")
                            .content("{\"operationId\":\"" + operationId + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        }
        mvc.perform(get("/api/threads/" + id).session(browser.session))
                .andExpect(jsonPath("$.context.seatNumber").value("15C"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.pendingOperations.length()").value(0));
        mvc.perform(post("/api/threads/" + id + "/seats").session(browser.session)
                        .header("X-CSRF-Token", browser.csrf).contentType("application/json")
                        .content("{\"seatNumber\":\"16C\",\"version\":" + version + "}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/threads/" + id).session(browser.session).header("X-CSRF-Token", browser.csrf))
                .andExpect(status().isOk());
        mvc.perform(get("/api/threads/" + id).session(browser.session)).andExpect(status().isNotFound());
    }

    @Test
    void chatUsesModelChecksHandoffsAndReturnsState() throws Exception {
        when(model.complete(any(), any(), any())).thenReturn(
                AgentRunnerTest.check(true), AgentRunnerTest.check(true),
                AgentRunnerTest.call("handoff", "transfer_to_agent", "{\"target\":\"faq\"}"),
                AgentRunnerTest.call("lookup", "faq_lookup_tool", "{\"question\":\"Wi-Fi\"}"),
                new org.springframework.ai.chat.messages.AssistantMessage("演示免费Wi-Fi：Airline-Wifi。"));
        Browser browser = browser();
        String id = create(browser);
        mvc.perform(post("/api/threads/" + id + "/messages").session(browser.session)
                        .header("X-CSRF-Token", browser.csrf).contentType("application/json")
                        .content("{\"requestId\":\"chat-test\",\"message\":\"Wi-Fi政策？\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        var snapshot = mvc.perform(get("/api/threads/" + id).session(browser.session))
                .andExpect(jsonPath("$.activeAgentId").value("faq")).andReturn();
        JsonNode state = Json.read(snapshot.getResponse().getContentAsString());
        assertThat(state.path("guardrails").size()).isEqualTo(2);
        assertThat(state.path("messages").size()).isEqualTo(2);
        assertThat(state.path("runStatus").asString()).isEqualTo("COMPLETED");
        assertThat(state.path("events").toString()).contains("handoff", "toolCallId");
    }

    @Test
    void invalidRequestsReturnStructuredError() throws Exception {
        Browser browser = browser();
        String id = create(browser);
        mvc.perform(post("/api/threads/" + id + "/messages").session(browser.session)
                        .header("X-CSRF-Token", browser.csrf).contentType("application/json")
                        .content("{\"requestId\":\"r\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private record Browser(MockHttpSession session, String csrf) {}
}
