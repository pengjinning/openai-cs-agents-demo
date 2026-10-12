package com.example.airline;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunnerTest {
    @Test
    void successfulSeatMapToolAddsPersistentMessageActionOnlyToThatTurn() {
        ScriptedModel model = new ScriptedModel(check(true), check(true),
                call("map", "display_seat_map", "{}"), new AssistantMessage("请查看座位图"),
                check(true), check(true), new AssistantMessage("还有什么需要帮助？"));
        Conversation conversation = new Conversation("owner", "on_time");
        conversation.activeAgent = "seat";
        AgentRunner runner = runner(model, new BusinessTools(), 16);
        runner.respond(conversation, "map-turn", "查看座位图");
        assertThat(conversation.messages.getLast().uiActions()).containsExactly("OPEN_SEAT_MAP");
        assertThat(conversation.snapshot(new AgentCatalog()).messages().getLast().uiActions())
                .containsExactly("OPEN_SEAT_MAP");
        runner.respond(conversation, "next-turn", "谢谢");
        assertThat(conversation.messages.getLast().uiActions()).isEmpty();
    }

    @Test
    void routingAgentNameIsConsistentWithoutChangingItsProtocolId() {
        AgentCatalog catalog = new AgentCatalog();
        assertThat(catalog.get("triage").name()).isEqualTo("路由Agent");
        assertThat(catalog.metadata()).anySatisfy(agent ->
                assertThat(agent).containsEntry("id", "triage").containsEntry("name", "路由Agent"));
        assertThat(catalog.prompt("triage")).contains("路由Agent").doesNotContain("分诊");
        assertThat(catalog.handoffDefinition("faq").description()).contains("triage（路由Agent）");
    }

    static AirlineProperties properties(int steps) {
        return new AirlineProperties("deepseek-v4-flash", steps, 24, 8,
                Duration.ofSeconds(120), Duration.ofSeconds(40), Duration.ofMinutes(60), 500);
    }

    static AssistantMessage call(String id, String tool, String args) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", tool, args))).build();
    }

    static AssistantMessage check(boolean passed) {
        return new AssistantMessage("{\"passed\":" + passed
                + ",\"reasoning\":\"test\",\"mood\":\"neutral\",\"categories\":[],\"humanRequested\":false}");
    }

    private AgentRunner runner(ScriptedModel model, BusinessTools tools, int steps) {
        AgentCatalog catalog = new AgentCatalog();
        EventHub events = new EventHub(catalog);
        return new AgentRunner(catalog, tools, model, new InputChecks(model), events, properties(steps), new HumanSupport(events));
    }

    @Test
    void handoffSwitchesPromptToolsAndMaintainsHistoryAcrossTurns() {
        ScriptedModel model = new ScriptedModel(check(true), check(true),
                call("handoff1", "transfer_to_agent", "{\"target\":\"seat\"}"),
                new AssistantMessage("希望换到哪个座位？"),
                check(true), check(true),
                call("seat1", "update_seat", "{\"confirmation_number\":\"LL0EZ6\",\"new_seat\":\"15C\"}"),
                new AssistantMessage("请在页面确认座位15C。"));
        Conversation conversation = new Conversation("owner", "on_time");
        AgentRunner runner = runner(model, new BusinessTools(), 16);
        assertThat(runner.respond(conversation, "one", "我想换座位").status()).isEqualTo("COMPLETED");
        assertThat(conversation.activeAgent).isEqualTo("seat");
        assertThat(runner.respond(conversation, "two", "15C").status()).isEqualTo("COMPLETED");
        assertThat(conversation.context.seatNumber).isEqualTo("23A");
        assertThat(conversation.pending).hasSize(1);
        assertThat(model.tools.get(2)).contains("get_trip_details").doesNotContain("update_seat");
        assertThat(model.tools.get(3)).contains("update_seat").doesNotContain("get_trip_details");
        assertThat(model.messages.get(3).getFirst().getText()).contains("座位");
        assertThat(conversation.history.stream().filter(message -> message instanceof UserMessage)).hasSize(2);
        assertThat(conversation.history.stream().filter(message -> message instanceof ToolResponseMessage)).hasSize(2);
    }

    @Test
    void refusedInputCannotReachBusinessToolsAndHasTerminalEvent() {
        ScriptedModel model = new ScriptedModel(check(false), check(true));
        Conversation conversation = new Conversation("owner", "on_time");
        assertThat(runner(model, new BusinessTools(), 16).respond(conversation, "one", "写一首草莓诗").status()).isEqualTo("REJECTED");
        assertThat(conversation.history).isEmpty();
        assertThat(conversation.pending).isEmpty();
        assertThat(conversation.events.getLast().type()).isEqualTo("run_finished");
        assertThat(conversation.runLock.isLocked()).isFalse();
        assertThat(model.tools).allMatch(List::isEmpty);
    }

    @Test
    void malformedGuardrailIsErrorNotPass() {
        ScriptedModel model = new ScriptedModel(new AssistantMessage("not JSON"), check(true));
        Conversation conversation = new Conversation("owner", "on_time");
        assertThat(runner(model, new BusinessTools(), 16).respond(conversation, "one", "你好").status()).isEqualTo("FAILED");
        assertThat(conversation.guardrails.getFirst().status()).isEqualTo("ERROR");
        assertThat(conversation.pending).isEmpty();
    }

    @Test
    void forbiddenToolsAndAmbiguousHandoffsHaveNoEffects() {
        ScriptedModel model = new ScriptedModel(check(true), check(true),
                call("bad", "cancel_flight", "{}"));
        Conversation conversation = new Conversation("owner", "on_time");
        assertThat(runner(model, new BusinessTools(), 16).respond(conversation, "one", "取消").status()).isEqualTo("FAILED");
        assertThat(conversation.pending).isEmpty();
        assertThat(conversation.context.bookingStatus).isEqualTo("CONFIRMED");

        ScriptedModel mixed = new ScriptedModel(check(true), check(true),
                AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("a", "function", "transfer_to_agent", "{\"target\":\"booking\"}"),
                        new AssistantMessage.ToolCall("b", "function", "get_trip_details", "{\"message\":\"巴黎\"}"))).build());
        Conversation second = new Conversation("owner", null);
        assertThat(runner(mixed, new BusinessTools(), 16).respond(second, "two", "查航班").status()).isEqualTo("FAILED");
        assertThat(second.context.scenario).isNull();
    }

    @Test
    void replayIsIdempotentAndConflictingRequestIdIsRejected() {
        ScriptedModel model = new ScriptedModel(check(true), check(true), new AssistantMessage("你好"));
        Conversation conversation = new Conversation("owner", "on_time");
        AgentRunner runner = runner(model, new BusinessTools(), 16);
        AgentRunner.Reply original = runner.respond(conversation, "same", "你好");
        assertThat(runner.respond(conversation, "same", "你好")).isEqualTo(original);
        assertThat(conversation.messages).hasSize(2);
        assertThat(model.messages).hasSize(3);
        assertThatThrownBy(() -> runner.respond(conversation, "same", "不同消息")).isInstanceOf(ApiException.class);
    }

    @Test
    void budgetFailureIsExplicitAndPreservesCompleteToolProtocol() {
        ScriptedModel model = new ScriptedModel(check(true), check(true),
                call("loop", "transfer_to_agent", "{\"target\":\"faq\"}"));
        Conversation conversation = new Conversation("owner", null);
        AgentRunner.Reply reply = runner(model, new BusinessTools(), 1).respond(conversation, "one", "政策");
        assertThat(reply.status()).isEqualTo("FAILED");
        assertThat(reply.message()).contains("步数");
        assertThat(conversation.history.getLast()).isInstanceOf(ToolResponseMessage.class);
        assertThat(conversation.activeAgent).isEqualTo("faq");
        assertThat(conversation.runLock.isLocked()).isFalse();
    }

    @Test
    void handoffSchemasAndPromptsExposeOnlyCurrentRoleTargets() {
        AgentCatalog catalog = new AgentCatalog();
        for (AgentCatalog.Agent agent : catalog.all()) {
            var schema = Json.read(catalog.handoffDefinition(agent.id()).inputSchema());
            List<String> targets = new ArrayList<>();
            schema.path("properties").path("target").path("enum").forEach(value -> targets.add(value.asString()));
            assertThat(targets).containsExactlyElementsOf(agent.handoffs());
            assertThat(catalog.prompt(agent.id())).contains("当前客服角色 ID：" + agent.id(),
                    "精确 ID：" + String.join(", ", agent.handoffs()));
        }
    }

    @Test
    void disallowedHandoffStillFailsWithoutChangingAgentOrWritingBusinessState() {
        ScriptedModel model = new ScriptedModel(check(true), check(true),
                call("invalid-route", "transfer_to_agent", "{\"target\":\"booking\"}"));
        Conversation conversation = new Conversation("owner", "on_time");
        conversation.activeAgent = "faq";
        assertThat(runner(model, new BusinessTools(), 16).respond(conversation, "one", "我要改签").status())
                .isEqualTo("FAILED");
        assertThat(conversation.activeAgent).isEqualTo("faq");
        assertThat(conversation.pending).isEmpty();
        assertThat(conversation.events.stream().filter(event -> event.type().equals("handoff_rejected")))
                .singleElement().satisfies(event -> {
                    assertThat(event.metadata()).containsEntry("sourceAgent", "faq").containsEntry("requestedTarget", "booking");
                });
        assertThat(conversation.history.stream().filter(message -> message instanceof AssistantMessage)).isEmpty();
    }

    @Test
    void toolExceptionRecordsFailuresForAllCallsAndPreservesEarlierResults() {
        BusinessTools tools = mock(BusinessTools.class);
        BusinessTools actual = new BusinessTools();
        when(tools.get(anyString())).thenAnswer(invocation -> actual.get(invocation.getArgument(0)));
        when(tools.modelContext(any())).thenReturn(Map.of("demo", true));
        when(tools.execute(any(), anyString(), any())).thenThrow(new IllegalStateException("backend failed"));
        ScriptedModel model = new ScriptedModel(check(true), check(true),
                AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("a", "function", "faq_lookup_tool", "{\"question\":\"wifi\"}"),
                        new AssistantMessage.ToolCall("b", "function", "faq_lookup_tool", "{\"question\":\"seats\"}"))).build());
        Conversation conversation = new Conversation("owner", "on_time");
        conversation.activeAgent = "faq";
        assertThat(runner(model, tools, 16).respond(conversation, "one", "政策").status()).isEqualTo("FAILED");
        ToolResponseMessage response = (ToolResponseMessage) conversation.history.getLast();
        assertThat(response.getResponses()).hasSize(2);
        assertThat(response.getResponses().get(0).responseData()).contains("TOOL_EXECUTION_ERROR");
        assertThat(response.getResponses().get(1).responseData()).contains("NOT_EXECUTED");
    }

    static final class ScriptedModel implements ModelGateway {
        final Queue<AssistantMessage> responses = new ArrayDeque<>();
        final List<List<Message>> messages = new ArrayList<>();
        final List<List<String>> tools = new ArrayList<>();

        ScriptedModel(AssistantMessage... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public AssistantMessage complete(List<Message> messages, List<ToolDefinition> tools, Duration timeout) {
            this.messages.add(List.copyOf(messages));
            this.tools.add(tools.stream().map(ToolDefinition::name).toList());
            if (responses.isEmpty()) {
                throw new AssertionError("Unexpected model call");
            }
            return responses.remove();
        }
    }
}
