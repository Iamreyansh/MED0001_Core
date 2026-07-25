package com.nammamedmate.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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
  void jdbcOutboxStoreDelegatesToJdbc() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcOutboxStore store = new JdbcOutboxStore(jdbc);
    OutboxMessage message = OutboxMessage.pending("auth.refresh_token_reused", "{\"a\":1}");

    store.append(message);
    verify(jdbc)
        .update(
            anyString(),
            eq(message.id()),
            eq(message.type()),
            eq(message.payloadJson()),
            eq(Timestamp.from(message.createdAt())),
            eq(false));

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id", UUID.class)).thenReturn(message.id());
    when(rs.getString("type")).thenReturn(message.type());
    when(rs.getString("payload_json")).thenReturn(message.payloadJson());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(message.createdAt()));
    when(rs.getBoolean("published")).thenReturn(message.published());
    when(jdbc.query(anyString(), any(RowMapper.class), anyInt()))
        .thenAnswer(
            invocation -> {
              RowMapper<OutboxMessage> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findUnpublished(5)).containsExactly(message);

    store.markPublished(message);
    verify(jdbc).update(anyString(), eq(message.id()));
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
