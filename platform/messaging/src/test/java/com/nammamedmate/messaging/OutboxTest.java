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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

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
    assertThat(multi.findUnpublished(10)).isEmpty();
    multi.markFailed(keep, "retry");
    assertThat(multi.all()).hasSize(2);
  }

  @Test
  void dispatchStopsOnTransportFailure() {
    InMemoryOutboxStore store = new InMemoryOutboxStore();
    store.append(OutboxMessage.pending("first", "{}"));
    store.append(OutboxMessage.pending("second", "{}"));
    SqsEventDispatcher dispatcher =
        new SqsEventDispatcher(
            store,
            message -> {
              if ("first".equals(message.type())) {
                throw new RuntimeException("transport down");
              }
            },
            10);
    assertThat(dispatcher.dispatchOnce()).isEqualTo(0);
    assertThat(store.findUnpublished(10)).hasSize(2);
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
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), anyInt()))
        .thenAnswer(
            invocation -> {
              RowMapper<OutboxMessage> mapper = invocation.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.claimUnpublished(5, "w1", Duration.ofMinutes(1))).containsExactly(message);
    assertThat(store.claimUnpublished(5, null, null)).containsExactly(message);
    assertThat(store.claimUnpublished(5, "  ", Duration.ZERO)).containsExactly(message);
    assertThat(store.claimUnpublished(5, "w1", Duration.ofSeconds(-1))).containsExactly(message);
    assertThat(store.findUnpublished(5)).containsExactly(message);

    store.markPublished(message);
    verify(jdbc).update(anyString(), any(Timestamp.class), eq(message.id()));
    store.markFailed(message, null);
    verify(jdbc).update(anyString(), eq("transport failed"), eq(message.id()));
    store.markFailed(message, "boom");
    verify(jdbc).update(anyString(), eq("boom"), eq(message.id()));
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

  @Test
  void sqsTransportSendsAndRejectsBlankUrl() {
    SqsClient sqs = mock(SqsClient.class);
    OutboxMessage message = OutboxMessage.pending("order.created", "{\"type\":\"order.created\"}");
    new SqsOutboxTransport(sqs, "https://sqs.example/q").accept(message);
    verify(sqs).sendMessage(any(SendMessageRequest.class));
    assertThatThrownBy(() -> new SqsOutboxTransport(sqs, "  ").accept(message))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new SqsOutboxTransport(sqs, null).accept(message))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void schedulerLeaseUpdateOrInsert() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SchedulerLease lease = new SchedulerLease(jdbc, Clock.systemUTC());
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    assertThat(lease.tryAcquire("job", Duration.ofMinutes(1))).isTrue();
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    assertThat(lease.tryAcquire("job", Duration.ZERO)).isTrue();
    when(jdbc.update(anyString(), any(), any(), any())).thenThrow(new RuntimeException("dup"));
    assertThat(lease.tryAcquire("job", null)).isFalse();
    assertThat(lease.tryAcquire(" ", Duration.ofSeconds(1))).isFalse();
    assertThat(lease.tryAcquire(null, Duration.ofSeconds(1))).isFalse();
    SchedulerLease systemClock = new SchedulerLease(jdbc, null);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    assertThat(systemClock.tryAcquire("job", Duration.ofMinutes(1))).isTrue();
    assertThat(lease.tryAcquire("job", Duration.ofSeconds(-1))).isTrue();
  }
}
