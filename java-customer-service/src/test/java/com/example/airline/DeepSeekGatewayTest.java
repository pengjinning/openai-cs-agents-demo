package com.example.airline;

import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepSeekGatewayTest {
    @Test
    void interruptedStreamTimesOutAndDoesNotReturnPartialTextAsSuccess() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("部分回复"))))),
                Flux.never()));
        DeepSeekGateway gateway = new DeepSeekGateway(model, AgentRunnerTest.properties(16));
        try {
            assertThatThrownBy(() -> gateway.stream(List.of(new UserMessage("航班")), List.of(),
                    Duration.ofMillis(100), delta -> {}))
                    .isInstanceOf(ApiException.class).hasMessageContaining("超时");
        } finally {
            gateway.shutdown();
        }
    }

    @Test
    void streamingDeliversTextBeforeCompletionAndAggregatesTheFinalAnswer() throws Exception {
        ChatModel model = mock(ChatModel.class);
        CountDownLatch firstText = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.create(sink -> {
            sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("**NY")))));
            try {
                finish.await(2, TimeUnit.SECONDS);
                sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("950**")))));
                sink.complete();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                sink.error(exception);
            }
        }));
        DeepSeekGateway gateway = new DeepSeekGateway(model, AgentRunnerTest.properties(16));
        List<String> deltas = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var reply = executor.submit(() -> gateway.stream(List.of(new UserMessage("航班")), List.of(),
                    Duration.ofSeconds(3), delta -> { deltas.add(delta); firstText.countDown(); }));
            assertThat(firstText.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(reply.isDone()).isFalse();
            finish.countDown();
            assertThat(reply.get(2, TimeUnit.SECONDS).getText()).isEqualTo("**NY950**");
            assertThat(deltas).containsExactly("**NY", "950**");
        } finally {
            finish.countDown();
            gateway.shutdown();
        }
    }

    @Test
    void streamingToolRequestsAreReturnedButNotPublishedAsText() {
        ChatModel model = mock(ChatModel.class);
        AssistantMessage call = AgentRunnerTest.call("call", "cancel_flight", "{}");
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(call)))));
        DeepSeekGateway gateway = new DeepSeekGateway(model, AgentRunnerTest.properties(16));
        try {
            List<String> deltas = new ArrayList<>();
            AssistantMessage response = gateway.stream(List.of(new UserMessage("取消")), List.of(),
                    Duration.ofSeconds(2), deltas::add);
            assertThat(response.getToolCalls()).containsExactlyElementsOf(call.getToolCalls());
            assertThat(deltas).isEmpty();
        } finally {
            gateway.shutdown();
        }
    }

    @Test
    void usesDeepSeekOptionsAndReturnsToolCallWithoutExecutingIt() {
        ChatModel model = mock(ChatModel.class);
        AssistantMessage response = AgentRunnerTest.call("call", "cancel_flight", "{}");
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(response))));
        DeepSeekGateway gateway = new DeepSeekGateway(model, AgentRunnerTest.properties(16));
        try {
            assertThat(gateway.complete(List.of(new UserMessage("取消")),
                    List.of(new BusinessTools().get("cancel_flight").definition()), Duration.ofSeconds(2))).isEqualTo(response);
            ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
            verify(model).call(prompt.capture());
            DeepSeekChatOptions options = (DeepSeekChatOptions) prompt.getValue().getOptions();
            assertThat(options.getModel()).isEqualTo("deepseek-v4-flash");
            assertThat(options.getThinking())
                    .isEqualTo(org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionRequest.Thinking.DISABLED);
            assertThat(options.getToolCallbacks()).hasSize(1);
            assertThatThrownBy(() -> options.getToolCallbacks().getFirst().call("{}")).isInstanceOf(IllegalStateException.class);
        } finally {
            gateway.shutdown();
        }
    }

    @Test
    void timeoutIsExplicit() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(2000);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("late"))));
        });
        DeepSeekGateway gateway = new DeepSeekGateway(model, AgentRunnerTest.properties(16));
        try {
            assertThatThrownBy(() -> gateway.complete(List.of(new UserMessage("hi")), List.of(), Duration.ofMillis(30)))
                    .isInstanceOf(ApiException.class).hasMessageContaining("超时");
        } finally {
            gateway.shutdown();
        }
    }
}
