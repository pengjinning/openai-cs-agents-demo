package com.example.airline;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportFeaturesTest {
    private final BusinessTools tools = new BusinessTools();
    private final AgentCatalog catalog = new AgentCatalog();
    private final EventHub events = new EventHub(catalog);
    private final HumanSupport support = new HumanSupport(events);

    @Test
    void compoundRetrievalReturnsAllStableSourcesAndCompleteDetails() {
        Conversation conversation = new Conversation("owner", "disrupted");
        conversation.runId = "run";
        BusinessTools.Result result = tools.execute(conversation, "faq_lookup_tool",
                Map.of("question", "Wi‑Fi、行李、seat 布局、延误补偿？"));
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(conversation.currentSourceIds)
                .containsExactly("baggage-demo", "wifi-demo", "seating-demo", "compensation-demo");
        assertThat(conversation.context.policyConsulted).isTrue();
        PolicyKnowledge.Lookup lookup = conversation.knowledgeHistory.getFirst();
        assertThat(lookup.runId()).isEqualTo("run");
        assertThat(lookup.sources()).hasSize(4);
        for (PolicyKnowledge.Source source : lookup.sources()) {
            PolicyKnowledge.Document document = tools.source(conversation, source.id());
            assertThat(document.version()).isEqualTo(source.version()).isEqualTo("demo-v1");
            assertThat(document.content()).isEqualTo(source.snippet());
        }
        assertThat(tools.source(conversation, "seating-demo").content()).contains("136", "16", "24", "96");
        assertThatThrownBy(() -> tools.source(new Conversation("owner", null), "wifi-demo"))
                .isInstanceOf(ApiException.class).hasMessageContaining("没有查询");
        assertThatThrownBy(() -> tools.source(conversation, "../secret"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void unknownPolicyAndBoundedHistoryNeverInventSource() {
        Conversation conversation = new Conversation("owner", null);
        tools.execute(conversation, "faq_lookup_tool", Map.of("question", "wifi"));
        conversation.messages.add(new Conversation.ChatEntry("assistant", "查询结果", List.of("wifi-demo"), null, List.of()));
        for (int index = 0; index < 20; index++) {
            BusinessTools.Result result = tools.execute(conversation, "faq_lookup_tool", Map.of("question", "宠物托运"));
            assertThat(result.status()).isEqualTo("NOT_FOUND");
            assertThat(conversation.knowledgeHistory.getLast().sources()).isEmpty();
        }
        assertThat(conversation.knowledgeHistory).hasSize(15);
        assertThat(conversation.humanRecommended).isTrue();
        assertThat(conversation.context.policyConsulted).isFalse();
        assertThat(tools.source(conversation, "wifi-demo").id()).isEqualTo("wifi-demo");
    }

    @Test
    void invalidOrMissingInsightFieldsFailTheInputCheck() {
        for (String metadata : List.of("", ",\"mood\":\"angry\",\"categories\":[],\"humanRequested\":false",
                ",\"mood\":\"neutral\",\"categories\":[\"medical\"],\"humanRequested\":false",
                ",\"mood\":\"neutral\",\"categories\":[\"seat\",\"seat\"],\"humanRequested\":false",
                ",\"mood\":\"neutral\",\"categories\":[],\"humanRequested\":\"true\"")) {
            ModelGateway model = mock(ModelGateway.class, org.mockito.Mockito.CALLS_REAL_METHODS);
            when(model.complete(any(), any(), any())).thenReturn(
                    new AssistantMessage("{\"passed\":true,\"reasoning\":\"test\"" + metadata + "}"),
                    AgentRunnerTest.check(true));
            Conversation conversation = new Conversation("owner", "on_time");
            AgentRunner runner = new AgentRunner(catalog, tools, model, new InputChecks(model),
                    events, AgentRunnerTest.properties(16), support);
            assertThat(runner.respond(conversation, "id", "我要查询座位").status()).isEqualTo("FAILED");
            assertThat(conversation.guardrails.getFirst().status()).isEqualTo("ERROR");
            assertThat(conversation.history).isEmpty();
            assertThat(conversation.insights).isNull();
            assertThat(conversation.pending).isEmpty();
            assertThat(conversation.suggestedQuestions).contains("我想申请人工服务。");
            verify(model, times(2)).complete(any(), any(), any());
        }
    }

    @Test
    void explicitHumanIntentRegistersButMoodAloneOnlyRecommends() {
        for (boolean human : List.of(false, true)) {
            ModelGateway model = mock(ModelGateway.class, org.mockito.Mockito.CALLS_REAL_METHODS);
            when(model.complete(any(), any(), any())).thenReturn(
                    new AssistantMessage("{\"passed\":true,\"reasoning\":\"test\",\"mood\":\"frustrated\","
                            + "\"categories\":[\"flight\"],\"humanRequested\":" + human + "}"),
                    AgentRunnerTest.check(true), new AssistantMessage("我可以继续协助查询。"));
            Conversation conversation = new Conversation("owner", "disrupted");
            AgentRunner runner = new AgentRunner(catalog, tools, model, new InputChecks(model),
                    events, AgentRunnerTest.properties(16), support);
            AgentRunner.Reply reply = runner.respond(conversation, "id", human ? "我要人工" : "我对延误不满");
            assertThat(reply.status()).isEqualTo("COMPLETED");
            assertThat(HumanSupport.requested(conversation)).isEqualTo(human);
            assertThat(conversation.humanRecommended).isEqualTo(!human);
            assertThat(conversation.messages.getLast().insights().mood()).isEqualTo("frustrated");
            verify(model, times(human ? 2 : 3)).complete(any(), any(), any());
            if (human) {
                assertThat(reply.message()).contains("尚未接通真人", conversation.humanCases.getLast().id());
                assertThat(runner.respond(conversation, "id", "我要人工")).isSameAs(reply);
                assertThatThrownBy(() -> runner.respond(conversation, "other", "查航班"))
                        .isInstanceOf(ApiException.class).hasMessageContaining("恢复智能");
                assertThat(conversation.humanCases).hasSize(1);
            } else {
                assertThat(conversation.suggestedQuestions).hasSize(3).contains("我想申请人工服务。");
            }
        }
    }

    @Test
    void requestResumeAndLimitsAreIdempotentAndPreserveConfirmedBooking() {
        Conversation conversation = new Conversation("owner", "disrupted");
        BusinessTools.Result booked = tools.execute(conversation, "book_new_flight", Map.of("flight_number", "NY950"));
        assertThat(tools.confirm(conversation, booked.operationId()).status()).isEqualTo("SUCCESS");
        tools.execute(conversation, "update_seat",
                Map.of("confirmation_number", conversation.context.confirmationNumber, "new_seat", "15C"));
        conversation.messages.add(new Conversation.ChatEntry("user", "请帮我确认航程"));
        conversation.activeAgent = "seat";
        HumanSupport.Case first = support.request(conversation, "需要进一步说明");
        long sequence = conversation.sequence;
        assertThat(support.request(conversation, "重复")).isSameAs(first);
        assertThat(conversation.sequence).isEqualTo(sequence);
        assertThat(conversation.context.flightNumber).isEqualTo("NY950");
        assertThat(conversation.pending).isEmpty();
        assertThat(first.summary()).hasSize(1);
        assertThat(first.summary().getFirst().content()).isEqualTo("请帮我确认航程");
        assertThatThrownBy(() -> HumanSupport.requireAutomated(conversation)).isInstanceOf(ApiException.class);
        HumanSupport.Case resumed = support.resume(conversation);
        sequence = conversation.sequence;
        assertThat(support.resume(conversation)).isSameAs(resumed);
        assertThat(conversation.sequence).isEqualTo(sequence);
        assertThat(conversation.activeAgent).isEqualTo("triage");
        assertThat(HumanSupport.requested(conversation)).isFalse();
        assertThat(conversation.history.getLast().getText()).contains("恢复智能服务");
        for (int index = 1; index < 8; index++) {
            support.request(conversation, "另一个问题");
            support.resume(conversation);
        }
        assertThat(conversation.humanCases).hasSize(8);
        assertThatThrownBy(() -> support.request(conversation, "第九次")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> support.request(conversation, " ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> support.resume(new Conversation("owner", null))).isInstanceOf(ApiException.class);
    }

    @Test
    void suggestionsReflectCancellationVouchersAndNewRoundSourceAssociation() {
        Conversation conversation = new Conversation("owner", "disrupted");
        conversation.insights = new ServiceInsights("curious", List.of("flight"), false);
        conversation.runStatus = "COMPLETED";
        assertThat(ServiceInsights.suggestions(conversation)).contains("延误后有哪些演示补偿政策？");
        conversation.context.vouchers = List.of("hotel");
        assertThat(ServiceInsights.suggestions(conversation)).doesNotContain("延误后有哪些演示补偿政策？");
        conversation.context.bookingStatus = "CANCELLED";
        assertThat(ServiceInsights.suggestions(conversation)).hasSize(2)
                .noneMatch(question -> question.contains("改签") || question.contains("座位"));

        ModelGateway model = mock(ModelGateway.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(model.complete(any(), any(), any())).thenReturn(
                AgentRunnerTest.check(true), AgentRunnerTest.check(true),
                AgentRunnerTest.call("handoff", "transfer_to_agent", "{\"target\":\"faq\"}"),
                AgentRunnerTest.call("lookup", "faq_lookup_tool", "{\"question\":\"wifi\"}"),
                new AssistantMessage("演示网络说明。"),
                AgentRunnerTest.check(true), AgentRunnerTest.check(true), new AssistantMessage("不客气。"));
        Conversation fresh = new Conversation("owner", "on_time");
        AgentRunner runner = new AgentRunner(catalog, tools, model, new InputChecks(model),
                events, AgentRunnerTest.properties(16), support);
        runner.respond(fresh, "first", "wifi");
        assertThat(fresh.messages.getLast().sourceIds()).containsExactly("wifi-demo");
        runner.respond(fresh, "second", "谢谢");
        assertThat(fresh.messages.getLast().sourceIds()).isEmpty();
        assertThat(fresh.messages.get(1).sourceIds()).containsExactly("wifi-demo");
        assertThat(fresh.knowledgeHistory).hasSize(1);
    }

    @Test
    void concurrentHumanActionsRejectBusyThreadAndSummaryIsBounded() throws Exception {
        Conversation conversation = new Conversation("owner", "on_time");
        conversation.runLock.lock();
        try {
            java.util.concurrent.CompletableFuture<Void> action = java.util.concurrent.CompletableFuture.runAsync(() -> {
                assertThatThrownBy(() -> support.request(conversation, "需要帮助"))
                        .isInstanceOf(ApiException.class).hasMessageContaining("正在处理");
                assertThatThrownBy(() -> support.resume(conversation))
                        .isInstanceOf(ApiException.class).hasMessageContaining("正在处理");
            });
            action.join();
        } finally {
            conversation.runLock.unlock();
        }
        for (int index = 0; index < 15; index++) {
            conversation.messages.add(new Conversation.ChatEntry("user", "x".repeat(1000)));
        }
        HumanSupport.Case supportCase = support.request(conversation, "需要帮助");
        assertThat(supportCase.summary()).hasSize(10)
                .allMatch(entry -> entry.content().length() == 500 && entry.sourceIds().isEmpty() && entry.insights() == null);
    }
}
