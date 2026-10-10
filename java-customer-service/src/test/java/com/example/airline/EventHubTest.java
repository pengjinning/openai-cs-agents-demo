package com.example.airline;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EventHubTest {
    @Test
    void failedSendRemovesListenerWithoutCompletingErroredResponseAgain() throws Exception {
        EventHub hub = new EventHub(new AgentCatalog());
        Conversation conversation = new Conversation("owner", "on_time");
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        conversation.listeners.add(emitter);
        hub.heartbeat(conversation);
        assertThat(conversation.listeners).isEmpty();
        verify(emitter, never()).complete();
        verify(emitter, never()).completeWithError(any());
    }
}
