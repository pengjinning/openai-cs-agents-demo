package com.example.airline;

import java.time.Duration;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.definition.ToolDefinition;

public interface ModelGateway {
    AssistantMessage complete(List<Message> messages, List<ToolDefinition> tools, Duration timeout);
}
