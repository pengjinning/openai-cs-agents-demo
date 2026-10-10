package com.example.airline;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationStoreTest {
    @Test
    void isolatesOwnersEnforcesCapacityAndExpiresState() {
        AirlineProperties properties = new AirlineProperties("deepseek-v4-flash", 16, 24, 8,
                Duration.ofSeconds(120), Duration.ofSeconds(40), Duration.ofMinutes(60), 1);
        EventHub events = new EventHub(new AgentCatalog());
        ConversationStore store = new ConversationStore(properties, events);
        Conversation conversation = store.create("owner", "on_time");
        assertThatThrownBy(() -> store.get(conversation.id, "other")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> store.create("owner", null)).isInstanceOf(ApiException.class);
        conversation.touchedAt = Instant.now().minusSeconds(7200);
        store.maintain();
        assertThatThrownBy(() -> store.get(conversation.id, "owner")).isInstanceOf(ApiException.class);
        assertThat(store.create("owner", null).context.scenario).isNull();
    }

    @Test
    void inFlightConversationCannotBeDeletedOrExpired() throws Exception {
        EventHub events = new EventHub(new AgentCatalog());
        ConversationStore store = new ConversationStore(AgentRunnerTest.properties(16), events);
        Conversation conversation = store.create("owner", "on_time");
        conversation.runLock.lock();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var delete = executor.submit(() -> {
                assertThatThrownBy(() -> store.delete(conversation.id, "owner"))
                        .isInstanceOf(ApiException.class).hasMessageContaining("正在执行");
                conversation.touchedAt = Instant.now().minusSeconds(7200);
                store.maintain();
            });
            delete.get(2, TimeUnit.SECONDS);
        } finally {
            conversation.runLock.unlock();
        }
        assertThat(store.get(conversation.id, "owner")).isSameAs(conversation);
    }

    @Test
    void eventWindowIsBoundedAndListenersHaveExplicitLimit() {
        EventHub events = new EventHub(new AgentCatalog());
        Conversation conversation = new Conversation("owner", "on_time");
        for (int index = 0; index < 305; index++) {
            events.record(conversation, "progress", "test", java.util.Map.of());
        }
        assertThat(conversation.events).hasSize(300);
        assertThat(conversation.sequence).isEqualTo(305);
        assertThat(conversation.events.getFirst().sequence()).isEqualTo(6);
        for (int index = 0; index < 4; index++) {
            events.subscribe(conversation);
        }
        assertThatThrownBy(() -> events.subscribe(conversation)).isInstanceOf(ApiException.class);
        events.close(conversation);
        assertThat(conversation.listeners).isEmpty();
    }
}
