package com.example.airline;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.definition.ToolDefinition;

public interface ModelGateway {
    AssistantMessage complete(List<Message> messages, List<ToolDefinition> tools, Duration timeout);

    default AssistantMessage stream(List<Message> messages, List<ToolDefinition> tools, Duration timeout,
                                    Consumer<String> onText) {
        AssistantMessage response = complete(messages, tools, timeout);
        if (!response.hasToolCalls() && response.getText() != null) {
            onText.accept(response.getText());
        }
        return response;
    }
}
