package com.example.airline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.ai.chat.messages.Message;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class Conversation {
    public record Event(String id, String threadId, String runId, long sequence, String type,
                        String agent, String content, long timestamp, Map<String, Object> metadata) {}
    public record ChatEntry(String role, String content, List<String> sourceIds,
                            ServiceInsights insights, List<String> suggestedQuestions, List<String> uiActions) {
        public ChatEntry(String role, String content, List<String> sourceIds,
                         ServiceInsights insights, List<String> suggestedQuestions) {
            this(role, content, sourceIds, insights, suggestedQuestions, List.of());
        }
        public ChatEntry(String role, String content) {
            this(role, content, List.of(), null, List.of());
        }
    }
    public record Guardrail(String name, String status, String reasoning) {}
    public record Operation(String id, String tool, Map<String, String> arguments,
                            Map<String, String> booking, Instant expiresAt) {}
    public record StreamingReply(String runId, int step, String content) {}
    public record Snapshot(String threadId, String activeAgentId, String runStatus, long revision,
                           long eventCursor, Map<String, Object> context, List<ChatEntry> messages,
                           List<Event> events, List<Guardrail> guardrails, List<Operation> pendingOperations,
                           List<Map<String, Object>> agents, ServiceInsights insights, List<String> suggestedQuestions,
                           boolean humanRecommended, List<PolicyKnowledge.Lookup> knowledgeHistory,
                           List<HumanSupport.Case> humanCases, StreamingReply streamingReply) {}

    final String id = UUID.randomUUID().toString();
    final String ownerId;
    final ReentrantLock runLock = new ReentrantLock();
    final AirlineData.Context context = new AirlineData.Context();
    final List<Message> history = new ArrayList<>();
    final List<ChatEntry> messages = new ArrayList<>();
    final List<Event> events = new ArrayList<>();
    final List<SseEmitter> listeners = new ArrayList<>();
    final Map<String, Operation> pending = new LinkedHashMap<>();
    final Map<String, BusinessTools.Result> completed = new LinkedHashMap<>();
    final Map<String, AgentRunner.Reply> requests = new LinkedHashMap<>();
    final Map<String, String> requestTexts = new LinkedHashMap<>();
    final List<PolicyKnowledge.Lookup> knowledgeHistory = new ArrayList<>();
    final List<String> currentSourceIds = new ArrayList<>();
    final List<HumanSupport.Case> humanCases = new ArrayList<>();
    ServiceInsights insights;
    List<String> suggestedQuestions = List.of();
    boolean humanRecommended;
    List<Guardrail> guardrails = List.of();
    String activeAgent = "triage";
    String runId;
    String runStatus = "IDLE";
    StreamingReply streamingReply;
    long revision;
    long sequence;
    Instant touchedAt = Instant.now();

    Conversation(String ownerId, String scenario) {
        this.ownerId = ownerId;
        if (scenario != null) {
            context.load(scenario);
        }
    }

    synchronized Snapshot snapshot(AgentCatalog catalog) {
        Map<String, Object> visible = new LinkedHashMap<>();
        visible.put("demo", true);
        visible.put("bookingStatus", context.bookingStatus);
        visible.put("seatMapVersion", context.seatMapVersion);
        if (context.passengerName != null) {
            visible.put("passengerName", context.passengerName);
            visible.put("confirmationNumber", context.confirmationNumber);
            visible.put("flightNumber", context.flightNumber);
            visible.put("seatNumber", context.seatNumber);
        }
        if (!context.vouchers.isEmpty()) {
            visible.put("vouchers", context.vouchers);
        }
        return new Snapshot(id, activeAgent, runStatus, revision, sequence, Map.copyOf(visible),
                List.copyOf(messages), List.copyOf(events), List.copyOf(guardrails),
                pending.values().stream().filter(op -> op.expiresAt().isAfter(Instant.now()))
                        .map(op -> new Operation(op.id(), op.tool(), BusinessTools.publicArguments(op.arguments()),
                                op.booking(), op.expiresAt())).toList(),
                catalog.metadata(), insights, List.copyOf(suggestedQuestions), humanRecommended,
                List.copyOf(knowledgeHistory), List.copyOf(humanCases), streamingReply);
    }
}
