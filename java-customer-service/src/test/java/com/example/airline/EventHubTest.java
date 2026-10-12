package com.example.airline;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

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
    void partialReplyIsRecoverableWithoutAddingChatHistoryOrEventLogEntries() {
        AgentCatalog catalog = new AgentCatalog();
        EventHub hub = new EventHub(catalog);
        Conversation conversation = new Conversation("owner", "disrupted");
        conversation.runId = UUID.randomUUID().toString();
        conversation.runStatus = "RUNNING";
        hub.startReply(conversation, 1);
        long revision = conversation.revision;
        hub.appendReply(conversation, conversation.runId, 1, "**NY");
        hub.appendReply(conversation, conversation.runId, 1, "950**");
        assertThat(conversation.snapshot(catalog).streamingReply().content()).isEqualTo("**NY950**");
        assertThat(conversation.revision).isEqualTo(revision + 2);
        assertThat(conversation.messages).isEmpty();
        assertThat(conversation.events).isEmpty();
        hub.startReply(conversation, 2);
        hub.appendReply(conversation, "old-run", 2, "过期回复");
        hub.appendReply(conversation, conversation.runId, 1, "过期步骤");
        assertThat(conversation.streamingReply.content()).isEmpty();
        conversation.runStatus = "FAILED";
        conversation.streamingReply = null;
        hub.record(conversation, "run_finished", "失败", Map.of());
        hub.appendReply(conversation, conversation.runId, 2, "迟到回复");
        assertThat(conversation.snapshot(catalog).streamingReply()).isNull();
    }

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
