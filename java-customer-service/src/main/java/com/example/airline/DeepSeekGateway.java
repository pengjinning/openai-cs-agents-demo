package com.example.airline;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekGateway implements ModelGateway {
    private final ChatModel model;
    private final AirlineProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(8);

    public DeepSeekGateway(ChatModel model, AirlineProperties properties) {
        this.model = model;
        this.properties = properties;
    }

    @Override
    public AssistantMessage complete(List<Message> messages, List<ToolDefinition> tools, Duration timeout) {
        if (!slots.tryAcquire()) {
            throw new ApiException(503, "MODEL_BUSY", "模型请求容量已满，请稍后重试");
        }
        var future = executor.submit(() -> {
            try {
                List<ToolCallback> callbacks = tools.stream().<ToolCallback>map(definition -> new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return definition;
                    }

                    @Override
                    public String call(String arguments) {
                        throw new IllegalStateException("Tools must be executed by AgentRunner");
                    }
                }).toList();
                var options = DeepSeekChatOptions.builder().model(properties.model())
                        .disableThinking().temperature(0.2).toolCallbacks(callbacks).build();
                // Direct ChatModel.call returns tool requests in 2.0.1; no ChatClient tool advisor runs here.
                ChatResponse response = model.call(new Prompt(messages, options));
                if (response == null || response.getResult() == null) {
                    throw new ApiException(502, "EMPTY_MODEL_RESPONSE", "模型没有返回有效结果");
                }
                return response.getResult().getOutput();
            } finally {
                slots.release();
            }
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new ApiException(504, "MODEL_TIMEOUT", "模型调用超时；尚未执行的业务操作不会提交");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApiException(503, "MODEL_INTERRUPTED", "模型调用被中断");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(502, "MODEL_UNAVAILABLE", "DeepSeek 调用失败，请检查服务端配置或稍后重试");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
