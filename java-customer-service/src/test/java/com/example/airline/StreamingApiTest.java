package com.example.airline;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ai.chat.messages.AssistantMessage;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.deepseek.api-key=local-test-placeholder")
class StreamingApiTest {
    @LocalServerPort int port;
    @MockitoBean ModelGateway model;

    @Test
    void realSseDeliversPartialTextBeforePostCompletesAndSnapshotRecoversIt() throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        when(model.complete(any(), any(), any())).thenReturn(AgentRunnerTest.check(true));
        when(model.stream(any(), any(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> onText = invocation.getArgument(3);
            onText.accept("**NY");
            if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
            onText.accept("950**");
            return new AssistantMessage("**NY950**");
        });
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .proxy(ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(3)).build();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String csrf = Json.read(client.send(request("/api/session").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body()).path("csrfToken").asString();
            HttpResponse<String> created = client.send(request("/api/threads")
                    .header("X-CSRF-Token", csrf).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"scenario\":\"disrupted\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            String thread = Json.read(created.body()).path("threadId").asString();
            String url = "/api/threads/" + thread;
            HttpResponse<java.io.InputStream> sse = client.send(request(url + "/events").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertThat(sse.statusCode()).isEqualTo(200);
            try (var reader = new BufferedReader(new InputStreamReader(sse.body(), StandardCharsets.UTF_8))) {
                var firstDelta = executor.submit(() -> {
                    String line;
                    boolean deltaEvent = false;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("event:")) deltaEvent = line.contains("reply_delta");
                        if (deltaEvent && line.startsWith("data:")) return Json.read(line.substring(5).trim());
                    }
                    throw new IllegalStateException("No reply delta received");
                });
                var post = client.sendAsync(request(url + "/messages")
                        .header("X-CSRF-Token", csrf).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"requestId\":\"stream-test\",\"message\":\"查看航班\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode delta = firstDelta.get(3, TimeUnit.SECONDS);
                assertThat(delta.path("delta").asString()).isEqualTo("**NY");
                assertThat(post.isDone()).isFalse();
                JsonNode partial = Json.read(client.send(request(url).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body());
                assertThat(partial.path("runStatus").asString()).isEqualTo("RUNNING");
                assertThat(partial.path("streamingReply").path("content").asString()).isEqualTo("**NY");
                assertThat(partial.path("messages").size()).isEqualTo(1);
                finish.countDown();
                assertThat(post.get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                JsonNode completed = Json.read(client.send(request(url).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body());
                assertThat(completed.path("messages").size()).isEqualTo(2);
                assertThat(completed.path("messages").get(1).path("content").asString()).isEqualTo("**NY950**");
                assertThat(completed.path("streamingReply").isNull()).isTrue();
            } finally {
                finish.countDown();
            }
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
    }
}
