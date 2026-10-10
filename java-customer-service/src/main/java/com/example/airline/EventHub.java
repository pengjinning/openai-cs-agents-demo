package com.example.airline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class EventHub {
    private static final Logger LOG = LoggerFactory.getLogger(EventHub.class);
    private final AgentCatalog catalog;

    public EventHub(AgentCatalog catalog) {
        this.catalog = catalog;
    }

    public void record(Conversation conversation, String type, String content, Map<String, Object> metadata) {
        synchronized (conversation) {
            conversation.events.add(new Conversation.Event(UUID.randomUUID().toString(), conversation.id,
                    conversation.runId, ++conversation.sequence, type, conversation.activeAgent,
                    content, System.currentTimeMillis(), Map.copyOf(metadata)));
            if (conversation.events.size() > 300) {
                conversation.events.removeFirst();
            }
            conversation.revision++;
            broadcast(conversation, "snapshot", conversation.snapshot(catalog));
        }
    }

    public SseEmitter subscribe(Conversation conversation) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        synchronized (conversation) {
            if (conversation.listeners.size() >= 4) {
                throw new ApiException(429, "TOO_MANY_LISTENERS", "此会话的连接数量已达到上限");
            }
            conversation.listeners.add(emitter);
            Runnable cleanup = () -> {
                synchronized (conversation) {
                    conversation.listeners.remove(emitter);
                }
            };
            emitter.onCompletion(cleanup);
            emitter.onTimeout(() -> {
                cleanup.run();
                emitter.complete();
            });
            emitter.onError(error -> cleanup.run());
            send(conversation, emitter, "snapshot", conversation.snapshot(catalog));
        }
        return emitter;
    }

    public void heartbeat(Conversation conversation) {
        synchronized (conversation) {
            broadcast(conversation, "heartbeat", Map.of("timestamp", System.currentTimeMillis()));
        }
    }

    public void close(Conversation conversation) {
        synchronized (conversation) {
            for (SseEmitter emitter : new ArrayList<>(conversation.listeners)) {
                emitter.complete();
            }
            conversation.listeners.clear();
        }
    }

    private void broadcast(Conversation conversation, String name, Object data) {
        for (SseEmitter emitter : new ArrayList<>(conversation.listeners)) {
            send(conversation, emitter, name, data);
        }
    }

    private void send(Conversation conversation, SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).id(Long.toString(conversation.sequence)).data(data));
        } catch (IOException | IllegalStateException exception) {
            conversation.listeners.remove(emitter);
            // The servlet container owns completion after an I/O error; do not write or complete again.
            LOG.debug("Closed disconnected SSE listener for thread {}", conversation.id);
        }
    }
}
