package com.example.airline;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

@Component
public class HumanSupport {
    public record Case(String id, String status, String reason, Instant createdAt,
                       List<Conversation.ChatEntry> summary) {}
    private final EventHub events;

    public HumanSupport(EventHub events) {
        this.events = events;
    }

    public static boolean requested(Conversation conversation) {
        return !conversation.humanCases.isEmpty() && conversation.humanCases.getLast().status().equals("REQUESTED");
    }

    public static void requireAutomated(Conversation conversation) {
        if (requested(conversation)) {
            throw new ApiException(409, "HUMAN_SUPPORT_PENDING", "已登记人工服务申请，请先恢复智能服务再继续自动处理");
        }
    }

    public Case request(Conversation conversation, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ApiException(400, "INVALID_REASON", "请提供不超过500字的申请原因");
        }
        if (!conversation.runLock.tryLock()) {
            throw new ApiException(409, "THREAD_BUSY", "服务正在处理，请等待完成后申请人工服务");
        }
        try {
            synchronized (conversation) {
                if (requested(conversation)) {
                    return conversation.humanCases.getLast();
                }
                if (conversation.humanCases.size() >= 8) {
                    throw new ApiException(409, "HUMAN_CASE_LIMIT", "此演示会话申请次数达到上限，请创建新会话");
                }
                List<Conversation.ChatEntry> summary = conversation.messages.stream()
                        .skip(Math.max(0, conversation.messages.size() - 10))
                        .map(entry -> new Conversation.ChatEntry(entry.role(),
                                entry.content().substring(0, Math.min(500, entry.content().length())))).toList();
                Case supportCase = new Case("HUM-" + UUID.randomUUID(), "REQUESTED", reason.strip(), Instant.now(), summary);
                conversation.humanCases.add(supportCase);
                conversation.pending.clear();
                conversation.suggestedQuestions = List.of();
                conversation.humanRecommended = false;
                conversation.touchedAt = Instant.now();
                events.record(conversation, "human_requested", "已登记演示人工申请，尚未接通真人；智能服务已暂停",
                        Map.of("caseId", supportCase.id()));
                return supportCase;
            }
        } finally {
            conversation.runLock.unlock();
        }
    }

    public Case resume(Conversation conversation) {
        if (!conversation.runLock.tryLock()) {
            throw new ApiException(409, "THREAD_BUSY", "服务正在处理，请稍后恢复");
        }
        try {
            synchronized (conversation) {
                if (conversation.humanCases.isEmpty()) {
                    throw new ApiException(409, "NO_HUMAN_CASE", "当前没有人工服务申请");
                }
                Case current = conversation.humanCases.getLast();
                if (!requested(conversation)) {
                    return current;
                }
                Case cancelled = new Case(current.id(), "CANCELLED", current.reason(), current.createdAt(), current.summary());
                conversation.humanCases.set(conversation.humanCases.size() - 1, cancelled);
                conversation.activeAgent = "triage";
                conversation.touchedAt = Instant.now();
                conversation.history.add(new UserMessage("服务端状态通知：客户已取消演示人工申请并恢复智能服务。"));
                conversation.suggestedQuestions = ServiceInsights.suggestions(conversation);
                events.record(conversation, "human_resumed", "已取消演示人工申请，恢复智能服务", Map.of("caseId", cancelled.id()));
                return cancelled;
            }
        } finally {
            conversation.runLock.unlock();
        }
    }
}
