package com.example.airline;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ConversationStore {
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final AirlineProperties properties;
    private final EventHub events;

    public ConversationStore(AirlineProperties properties, EventHub events) {
        this.properties = properties;
        this.events = events;
    }

    public synchronized Conversation create(String owner, String scenario) {
        if (conversations.size() >= properties.maxSessions()) {
            throw new ApiException(503, "SESSION_LIMIT", "演示会话容量已满，请稍后重试");
        }
        Conversation conversation = new Conversation(owner, scenario);
        conversations.put(conversation.id, conversation);
        return conversation;
    }

    public Conversation get(String id, String owner) {
        Conversation conversation = conversations.get(id);
        if (conversation == null || !conversation.ownerId.equals(owner)) {
            throw new ApiException(404, "THREAD_NOT_FOUND", "会话不存在或不属于当前浏览器");
        }
        synchronized (conversation) {
            conversation.touchedAt = Instant.now();
        }
        return conversation;
    }

    public void delete(String id, String owner) {
        Conversation conversation = get(id, owner);
        if (!conversation.runLock.tryLock()) {
            throw new ApiException(409, "THREAD_BUSY", "会话正在执行，请等待完成后删除");
        }
        try {
            conversations.remove(id, conversation);
            events.close(conversation);
        } finally {
            conversation.runLock.unlock();
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void maintain() {
        for (Conversation conversation : conversations.values()) {
            synchronized (conversation) {
                if (conversation.touchedAt.plus(properties.sessionTtl()).isBefore(Instant.now())
                        && conversation.runLock.tryLock()) {
                    try {
                        if (conversations.remove(conversation.id, conversation)) {
                            events.close(conversation);
                        }
                    } finally {
                        conversation.runLock.unlock();
                    }
                } else {
                    events.heartbeat(conversation);
                }
            }
        }
    }
}
