package com.example.airline;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepSeekGatewayTest {
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
