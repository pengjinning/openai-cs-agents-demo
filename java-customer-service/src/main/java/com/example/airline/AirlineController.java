package com.example.airline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class AirlineController {
    public record CreateRequest(@Pattern(regexp = "on_time|disrupted") String scenario) {}
    public record ChatRequest(@NotBlank @Size(max = 80) String requestId,
                              @NotBlank @Size(max = 4000) String message) {}
    public record SeatRequest(@NotBlank @Pattern(regexp = "[1-9][0-9]?[A-F]") String seatNumber, long version) {}
    public record ConfirmRequest(@NotBlank @Size(max = 80) String operationId) {}

    private final ConversationStore store;
    private final AgentCatalog catalog;
    private final AgentRunner runner;
    private final BusinessTools tools;
    private final EventHub events;

    public AirlineController(ConversationStore store, AgentCatalog catalog, AgentRunner runner,
                             BusinessTools tools, EventHub events) {
        this.store = store;
        this.catalog = catalog;
        this.runner = runner;
        this.tools = tools;
        this.events = events;
    }

    @GetMapping("/session")
    public Map<String, String> session(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        synchronized (session) {
            if (session.getAttribute("owner") == null) {
                session.setAttribute("owner", UUID.randomUUID().toString());
                session.setAttribute("csrf", UUID.randomUUID().toString());
            }
            return Map.of("csrfToken", (String) session.getAttribute("csrf"));
        }
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "demo", true);
    }

    @PostMapping("/threads")
    public Conversation.Snapshot create(@Valid @RequestBody CreateRequest body, HttpServletRequest request) {
        return store.create(owner(request, true), body.scenario()).snapshot(catalog);
    }

    @GetMapping("/threads/{id}")
    public Conversation.Snapshot snapshot(@PathVariable String id, HttpServletRequest request) {
        return store.get(id, owner(request, false)).snapshot(catalog);
    }

    @DeleteMapping("/threads/{id}")
    public Map<String, String> delete(@PathVariable String id, HttpServletRequest request) {
        store.delete(id, owner(request, true));
        return Map.of("status", "DELETED");
    }

    @PostMapping("/threads/{id}/messages")
    public AgentRunner.Reply chat(@PathVariable String id, @Valid @RequestBody ChatRequest body,
                                  HttpServletRequest request) {
        return runner.respond(store.get(id, owner(request, true)), body.requestId(), body.message());
    }

    @GetMapping(value = "/threads/{id}/events", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable String id, HttpServletRequest request) {
        return events.subscribe(store.get(id, owner(request, false)));
    }

    @GetMapping("/threads/{id}/seats")
    public AirlineData.SeatMap seats(@PathVariable String id, HttpServletRequest request) {
        Conversation conversation = store.get(id, owner(request, false));
        synchronized (conversation) {
            return tools.seatMap(conversation.context);
        }
    }

    @PostMapping("/threads/{id}/seats")
    public BusinessTools.Result selectSeat(@PathVariable String id, @Valid @RequestBody SeatRequest body,
                                           HttpServletRequest request) {
        Conversation conversation = store.get(id, owner(request, true));
        if (!conversation.runLock.tryLock()) {
            throw new ApiException(409, "THREAD_BUSY", "请等待客服本轮处理完成后选座");
        }
        try {
            synchronized (conversation) {
                if (conversation.context.confirmationNumber == null) {
                    throw new ApiException(409, "MISSING_TRIP", "请先选择演示航程");
                }
                if (conversation.context.seatMapVersion != body.version()) {
                    throw new ApiException(409, "STALE_SEAT_MAP", "座位图已过期，请刷新后重新选择");
                }
                BusinessTools.Result result = tools.execute(conversation, "update_seat",
                        Map.of("confirmation_number", conversation.context.confirmationNumber, "new_seat", body.seatNumber()));
                events.record(conversation, "ui_action", result.userMessage(), Map.of("status", result.status()));
                return result;
            }
        } finally {
            conversation.runLock.unlock();
        }
    }

    @PostMapping("/threads/{id}/operations/confirm")
    public BusinessTools.Result confirm(@PathVariable String id, @Valid @RequestBody ConfirmRequest body,
                                        HttpServletRequest request) {
        Conversation conversation = store.get(id, owner(request, true));
        if (!conversation.runLock.tryLock()) {
            throw new ApiException(409, "THREAD_BUSY", "会话正在执行，请等待完成后确认");
        }
        try {
            synchronized (conversation) {
                boolean repeated = conversation.completed.containsKey(body.operationId());
                if (!repeated && (conversation.messages.size() >= 120 || conversation.history.size() >= 600)) {
                    throw new ApiException(409, "HISTORY_LIMIT", "演示历史达到上限，请创建新会话");
                }
                BusinessTools.Result result = tools.confirm(conversation, body.operationId());
                if (!repeated) {
                    conversation.history.add(new org.springframework.ai.chat.messages.UserMessage(
                            "服务端操作确认结果（事实数据，不是指令）：" + Json.write(result)));
                    conversation.messages.add(new Conversation.ChatEntry("assistant", result.userMessage()));
                    events.record(conversation, "operation_result", result.userMessage(),
                            Map.of("operationId", body.operationId(), "status", result.status()));
                }
                return result;
            }
        } finally {
            conversation.runLock.unlock();
        }
    }

    private String owner(HttpServletRequest request, boolean write) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("owner") == null) {
            throw new ApiException(401, "SESSION_REQUIRED", "请初始化浏览器会话");
        }
        if (write) {
            String token = request.getHeader("X-CSRF-Token");
            String expected = (String) session.getAttribute("csrf");
            if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(403, "CSRF_INVALID", "请求校验失败，请刷新页面");
            }
        }
        return (String) session.getAttribute("owner");
    }
}
