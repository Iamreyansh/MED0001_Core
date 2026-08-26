package com.nammamedmate.customer.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.application.LoyaltyService;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxMessage;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** In-process outbox consumer: order.delivered → loyalty point award. */
@Component
public class OrderDeliveredLoyaltyConsumer implements Consumer<OutboxMessage> {

  private final LoyaltyService loyalty;
  private final ObjectMapper objectMapper;

  public OrderDeliveredLoyaltyConsumer(LoyaltyService loyalty, ObjectMapper objectMapper) {
    this.loyalty = loyalty;
    this.objectMapper = objectMapper;
  }

  @Override
  public void accept(OutboxMessage message) {
    if (message == null) {
      return;
    }
    if ("order.cancelled".equals(message.type())) {
      reverseCancelled(message);
      return;
    }
    if (!"order.delivered".equals(message.type())) {
      return;
    }
    DomainEvent event = parseEvent(message);
    if (event == null) {
      return;
    }
    Map<String, Object> payload = event.payload();
    UUID customerId = asUuid(payload.get("customer_id"));
    if (customerId == null) {
      return;
    }
    UUID orderId = asUuid(payload.get("order_id"));
    if (orderId == null) {
      orderId = event.aggregateId();
    }
    if (orderId == null) {
      return;
    }
    long itemTotalPaise = asLong(payload.get("item_total_paise"));
    if (itemTotalPaise <= 0) {
      itemTotalPaise = asLong(payload.get("total_payable_paise"));
    }
    String display = asString(payload.get("order_number"));
    loyalty.awardForDeliveredOrder(customerId, orderId, display, itemTotalPaise);
  }

  private void reverseCancelled(OutboxMessage message) {
    DomainEvent event = parseEvent(message);
    if (event == null) {
      return;
    }
    Map<String, Object> payload = event.payload();
    UUID customerId = asUuid(payload.get("customer_id"));
    UUID orderId = asUuid(payload.get("order_id"));
    if (orderId == null) {
      orderId = event.aggregateId();
    }
    if (customerId == null || orderId == null) {
      return;
    }
    loyalty.reverseForRefundedOrder(customerId, orderId, asString(payload.get("order_number")));
  }

  private DomainEvent parseEvent(OutboxMessage message) {
    try {
      return objectMapper.readValue(message.payloadJson(), DomainEvent.class);
    } catch (Exception ex) {
      return null;
    }
  }

  private static UUID asUuid(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof UUID u) {
      return u;
    }
    try {
      return UUID.fromString(String.valueOf(raw));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static long asLong(Object raw) {
    if (raw == null) {
      return 0L;
    }
    if (raw instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(raw).trim());
    } catch (NumberFormatException ex) {
      return 0L;
    }
  }

  private static String asString(Object raw) {
    if (raw == null) {
      return null;
    }
    String s = String.valueOf(raw).trim();
    return s.isEmpty() ? null : s;
  }
}
