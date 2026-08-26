package com.nammamedmate.customer.adapter.in.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.application.LoyaltyService;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxMessage;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderDeliveredLoyaltyConsumerTest {

  private LoyaltyService loyalty;
  private ObjectMapper mapper;
  private OrderDeliveredLoyaltyConsumer consumer;

  @BeforeEach
  void setUp() {
    loyalty = mock(LoyaltyService.class);
    mapper = mock(ObjectMapper.class);
    consumer = new OrderDeliveredLoyaltyConsumer(loyalty, mapper);
  }

  @Test
  void ignoresBadMessages() throws Exception {
    consumer.accept(null);
    consumer.accept(new OutboxMessage(UUID.randomUUID(), "other", "{}", Instant.now(), false));
    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenThrow(new RuntimeException("bad"));
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(loyalty, never()).awardForDeliveredOrder(any(), any(), any(), anyLong());
  }

  @Test
  void awardsFromPayload() throws Exception {
    UUID customerId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(
            DomainEvent.of(
                "order.delivered",
                "order",
                orderId,
                Map.of(
                    "customer_id",
                    customerId.toString(),
                    "order_id",
                    orderId.toString(),
                    "item_total_paise",
                    58000,
                    "order_number",
                    "ORD-1")));
    when(loyalty.awardForDeliveredOrder(any(), any(), any(), anyLong()))
        .thenReturn(Optional.empty());
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(loyalty).awardForDeliveredOrder(eq(customerId), eq(orderId), eq("ORD-1"), eq(58000L));
  }

  @Test
  void fallsBackToTotalPayableAndAggregateId() throws Exception {
    UUID customerId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(
            DomainEvent.of(
                "order.delivered",
                "order",
                orderId,
                Map.of("customer_id", customerId, "total_payable_paise", "10000")));
    when(loyalty.awardForDeliveredOrder(any(), any(), any(), anyLong()))
        .thenReturn(Optional.empty());
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(loyalty).awardForDeliveredOrder(eq(customerId), eq(orderId), isNull(), eq(10000L));
  }

  @Test
  void skipsMissingIdsAndBlankOrderNumber() throws Exception {
    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(DomainEvent.of("order.delivered", "order", null, Map.of()));
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));

    UUID orderId = UUID.randomUUID();
    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(
            DomainEvent.of(
                "order.delivered",
                "order",
                orderId,
                Map.of("customer_id", "not-uuid", "item_total_paise", "x", "order_number", "  ")));
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));

    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(
            DomainEvent.of(
                "order.delivered",
                "order",
                null,
                Map.of("customer_id", UUID.randomUUID().toString())));
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(loyalty, never()).awardForDeliveredOrder(any(), any(), any(), anyLong());

    UUID customerId = UUID.randomUUID();
    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(
            DomainEvent.of(
                "order.delivered",
                "order",
                orderId,
                Map.of(
                    "customer_id",
                    customerId.toString(),
                    "order_id",
                    orderId.toString(),
                    "item_total_paise",
                    Integer.valueOf(1000),
                    "order_number",
                    "KEEP")));
    when(loyalty.awardForDeliveredOrder(any(), any(), any(), anyLong()))
        .thenReturn(Optional.empty());
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(loyalty).awardForDeliveredOrder(eq(customerId), eq(orderId), eq("KEEP"), eq(1000L));

    java.util.HashMap<String, Object> payload = new java.util.HashMap<>();
    payload.put("customer_id", customerId.toString());
    payload.put("order_id", orderId.toString());
    payload.put("order_number", "");
    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(DomainEvent.of("order.delivered", "order", orderId, payload));
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(loyalty).awardForDeliveredOrder(eq(customerId), eq(orderId), isNull(), eq(0L));

    UUID badAmt = UUID.randomUUID();
    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(
            DomainEvent.of(
                "order.delivered",
                "order",
                badAmt,
                Map.of(
                    "customer_id",
                    customerId.toString(),
                    "order_id",
                    badAmt.toString(),
                    "item_total_paise",
                    "not-a-number")));
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{}", Instant.now(), false));
    verify(loyalty).awardForDeliveredOrder(eq(customerId), eq(badAmt), isNull(), eq(0L));

    consumer.accept(new OutboxMessage(UUID.randomUUID(), null, "{}", Instant.now(), false));
  }

  @Test
  void reversesOnCancelled() throws Exception {
    UUID customerId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(
            DomainEvent.of(
                "order.cancelled",
                "order",
                orderId,
                Map.of(
                    "customer_id",
                    customerId.toString(),
                    "order_id",
                    orderId.toString(),
                    "order_number",
                    "ORD-C")));
    when(loyalty.reverseForRefundedOrder(any(), any(), any())).thenReturn(Optional.empty());
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.cancelled", "{}", Instant.now(), false));
    verify(loyalty).reverseForRefundedOrder(eq(customerId), eq(orderId), eq("ORD-C"));

    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenThrow(new RuntimeException("bad-cancel"));
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.cancelled", "{}", Instant.now(), false));

    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(DomainEvent.of("order.cancelled", "order", orderId, Map.of()));
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.cancelled", "{}", Instant.now(), false));

    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(
            DomainEvent.of(
                "order.cancelled", "order", orderId, Map.of("customer_id", customerId.toString())));
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.cancelled", "{}", Instant.now(), false));
    verify(loyalty).reverseForRefundedOrder(eq(customerId), eq(orderId), isNull());

    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(
            DomainEvent.of(
                "order.cancelled", "order", orderId, Map.of("order_id", orderId.toString())));
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.cancelled", "{}", Instant.now(), false));

    when(mapper.readValue(any(String.class), eq(DomainEvent.class)))
        .thenReturn(
            new DomainEvent(
                UUID.randomUUID(),
                "order.cancelled",
                "order",
                null,
                Instant.now(),
                Map.of("customer_id", customerId.toString())));
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.cancelled", "{}", Instant.now(), false));
  }
}
