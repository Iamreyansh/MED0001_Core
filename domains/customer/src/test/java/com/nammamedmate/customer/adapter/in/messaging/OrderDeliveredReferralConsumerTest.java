package com.nammamedmate.customer.adapter.in.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.application.ReferralService;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxMessage;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderDeliveredReferralConsumerTest {

  private ReferralService referrals;
  private ObjectMapper mapper;
  private OrderDeliveredReferralConsumer consumer;

  @BeforeEach
  void setUp() throws Exception {
    referrals = mock(ReferralService.class);
    mapper = mock(ObjectMapper.class);
    consumer = new OrderDeliveredReferralConsumer(referrals, mapper);
  }

  @Test
  void ignoresNonDeliveredAndBadPayload() throws Exception {
    consumer.accept(null);
    consumer.accept(new OutboxMessage(UUID.randomUUID(), null, "{}", Instant.now(), false));
    consumer.accept(new OutboxMessage(UUID.randomUUID(), "other", "{}", Instant.now(), false));
    verify(referrals, never()).onRefereeOrderDelivered(any(), any());

    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenThrow(new RuntimeException("bad"));
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(referrals, never()).onRefereeOrderDelivered(any(), any());

    when(mapper.readValue(any(String.class), eq(DomainEvent.class))).thenReturn(null);
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));

    DomainEvent emptyPayload =
        DomainEvent.of("order.delivered", "order", UUID.randomUUID(), Map.of());
    when(mapper.readValue(any(String.class), eq(DomainEvent.class))).thenReturn(emptyPayload);
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));

    DomainEvent missingIds =
        DomainEvent.of("order.delivered", "order", UUID.randomUUID(), Map.of("x", "y"));
    when(mapper.readValue(any(String.class), eq(DomainEvent.class))).thenReturn(missingIds);
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(referrals, never()).onRefereeOrderDelivered(any(), any());
  }

  @Test
  void dispatchesOnOrderDelivered() throws Exception {
    UUID customerId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    DomainEvent event =
        DomainEvent.of(
            "order.delivered",
            "order",
            orderId,
            Map.of("customer_id", customerId.toString(), "order_id", orderId.toString()));
    when(mapper.readValue(any(String.class), eq(DomainEvent.class))).thenReturn(event);
    when(referrals.onRefereeOrderDelivered(customerId, orderId)).thenReturn(Optional.empty());

    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(referrals).onRefereeOrderDelivered(customerId, orderId);

    DomainEvent uuidPayload =
        DomainEvent.of(
            "order.delivered",
            "order",
            orderId,
            Map.of("customer_id", customerId, "order_id", orderId));
    when(mapper.readValue(any(String.class), eq(DomainEvent.class))).thenReturn(uuidPayload);
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));

    DomainEvent aggregateOnly =
        DomainEvent.of(
            "order.delivered", "order", orderId, Map.of("customer_id", customerId.toString()));
    when(mapper.readValue(any(String.class), eq(DomainEvent.class))).thenReturn(aggregateOnly);
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(referrals, times(3)).onRefereeOrderDelivered(eq(customerId), eq(orderId));

    DomainEvent badUuid =
        DomainEvent.of("order.delivered", "order", orderId, Map.of("customer_id", "not-a-uuid"));
    when(mapper.readValue(any(String.class), eq(DomainEvent.class))).thenReturn(badUuid);
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(referrals, times(3)).onRefereeOrderDelivered(any(), any());

    DomainEvent nullAggregate =
        new DomainEvent(
            UUID.randomUUID(),
            "order.delivered",
            "order",
            null,
            Instant.now(),
            Map.of("customer_id", customerId.toString()));
    when(mapper.readValue(any(String.class), eq(DomainEvent.class))).thenReturn(nullAggregate);
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(referrals, times(3)).onRefereeOrderDelivered(any(), any());
  }
}
