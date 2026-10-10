package com.example.airline;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekProtocolTest {
    @Test
    void actualSpringAiDeepSeekSerializesSchemasAndRoundTripsToolHistory() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger toolResponses = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode request = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(request.path("model").asString()).isEqualTo("deepseek-v4-flash");
            assertThat(request.path("thinking").path("type").asString()).isEqualTo("disabled");
            int step = requests.incrementAndGet();
            for (JsonNode message : request.path("messages")) {
                if (message.path("role").asString().equals("tool")) {
                    toolResponses.incrementAndGet();
                }
            }
            Map<String, Object> message;
            String finish;
            if (step <= 2) {
                message = Map.of("role", "assistant", "content", "{\"passed\":true,\"reasoning\":\"local fixture\","
                        + "\"mood\":\"curious\",\"categories\":[\"policy\"],\"humanRequested\":false}");
                finish = "stop";
            } else if (step == 3 || step == 4) {
                String name = step == 3 ? "transfer_to_agent" : "faq_lookup_tool";
                String args = step == 3 ? "{\"target\":\"faq\"}" : "{\"question\":\"Wi-Fi\"}";
                assertThat(request.path("tools").toString()).contains(name, "additionalProperties");
                message = Map.of("role", "assistant", "content", "", "tool_calls", List.of(
                        Map.of("id", "call-" + step, "type", "function",
                                "function", Map.of("name", name, "arguments", args))));
                finish = "tool_calls";
            } else {
                message = Map.of("role", "assistant", "content", "演示免费 Wi-Fi：Airline-Wifi。");
                finish = "stop";
            }
            byte[] body = Json.write(Map.of("id", "completion-" + step, "object", "chat.completion",
                    "created", 1791612000, "model", "deepseek-v4-flash",
                    "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish)),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 10, "total_tokens", 20)))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        DeepSeekGateway gateway = null;
        try {
            DeepSeekApi api = DeepSeekApi.builder().apiKey("local-test-placeholder")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            gateway = new DeepSeekGateway(DeepSeekChatModel.builder().deepSeekApi(api).build(), AgentRunnerTest.properties(16));
            AgentCatalog catalog = new AgentCatalog();
            EventHub events = new EventHub(catalog);
            AgentRunner runner = new AgentRunner(catalog, new BusinessTools(), gateway,
                    new InputChecks(gateway), events, AgentRunnerTest.properties(16), new HumanSupport(events));
            Conversation conversation = new Conversation("owner", "on_time");
            AgentRunner.Reply reply = runner.respond(conversation, "protocol-test", "Wi-Fi政策？");
            assertThat(reply.status()).isEqualTo("COMPLETED");
            assertThat(reply.message()).contains("Airline-Wifi");
            assertThat(requests.get()).isEqualTo(5);
            assertThat(toolResponses.get()).isGreaterThanOrEqualTo(3);
            assertThat(conversation.activeAgent).isEqualTo("faq");
        } finally {
            if (gateway != null) {
                gateway.shutdown();
            }
            server.stop(0);
        }
    }
}
