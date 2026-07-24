package com.nammamedmate.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxTest {

  @Test
  void publishAndDispatch() {
    InMemoryOutboxStore store = new InMemoryOutboxStore();
    OutboxPublisher publisher = new OutboxPublisher(store, new ObjectMapper());
    DomainEvent event = DomainEvent.of("order.created", "order", UUID.randomUUID(), Map.of("x", 1));
    publisher.publish(event);
    assertThat(store.findUnpublished(10)).hasSize(1);
    List<OutboxMessage> sent = new ArrayList<>();
    SqsEventDispatcher dispatcher = new SqsEventDispatcher(store, sent::add, 0);
    assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
    assertThat(sent).hasSize(1);
    assertThat(store.findUnpublished(10)).isEmpty();
    assertThat(store.all()).hasSize(1);
    assertThat(store.all().getFirst().published()).isTrue();

    InMemoryOutboxStore multi = new InMemoryOutboxStore();
    OutboxMessage keep = OutboxMessage.pending("keep", "{}");
    OutboxMessage publish = OutboxMessage.pending("publish", "{}");
    multi.append(keep);
    multi.append(publish);
    multi.markPublished(publish);
    assertThat(multi.findUnpublished(10)).extracting(OutboxMessage::type).containsExactly("keep");
    assertThat(new SqsEventDispatcher(multi, m -> {}, 5).dispatchOnce()).isEqualTo(1);
  }

  @Test
  void domainEventCopiesNullPayload() {
    DomainEvent event =
        new DomainEvent(UUID.randomUUID(), "t", "a", UUID.randomUUID(), Instant.now(), null);
    assertThat(event.payload()).isEmpty();
  }

  @Test
  void serializeFailure() throws Exception {
    ObjectMapper mapper = mock(ObjectMapper.class);
    ObjectMapper copy = mock(ObjectMapper.class);
    when(mapper.copy()).thenReturn(copy);
    when(copy.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
    OutboxPublisher publisher = new OutboxPublisher(new InMemoryOutboxStore(), mapper);
    assertThatThrownBy(
            () -> publisher.publish(DomainEvent.of("t", "a", UUID.randomUUID(), Map.of("k", "v"))))
        .isInstanceOf(IllegalStateException.class);
  }
}
