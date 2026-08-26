package com.nammamedmate.customer.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.application.ReferralService;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxMessage;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** In-process outbox consumer: order.delivered → referral reward disbursement. */
@Component
public class OrderDeliveredReferralConsumer implements Consumer<OutboxMessage> {

  private final ReferralService referrals;
  private final ObjectMapper objectMapper;

  public OrderDeliveredReferralConsumer(ReferralService referrals, ObjectMapper objectMapper) {
    this.referrals = referrals;
    this.objectMapper = objectMapper;
  }

  @Override
  public void accept(OutboxMessage message) {
    if (message == null) {
      return;
    }
    if ("order.cancelled".equals(message.type())) {
      DomainEvent cancelled = parseEvent(message);
      if (cancelled == null) {
        return;
      }
      UUID customerId = asUuid(cancelled.payload().get("customer_id"));
      UUID orderId = asUuid(cancelled.payload().get("order_id"));
      if (orderId == null) {
        orderId = cancelled.aggregateId();
      }
      if (customerId != null && orderId != null) {
        referrals.onRefereeFirstOrderCancelled(customerId, orderId);
      }
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
    referrals.onRefereeOrderDelivered(customerId, orderId);
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
}
