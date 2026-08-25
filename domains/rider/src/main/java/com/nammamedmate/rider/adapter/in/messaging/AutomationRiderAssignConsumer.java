package com.nammamedmate.rider.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxMessage;
import com.nammamedmate.rider.application.DispatchService;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** automation.rider.assign_requested → auto-assign best rider for one order. */
@Component
public class AutomationRiderAssignConsumer implements Consumer<OutboxMessage> {

  private final DispatchService dispatch;
  private final ObjectMapper objectMapper;

  public AutomationRiderAssignConsumer(DispatchService dispatch, ObjectMapper objectMapper) {
    this.dispatch = dispatch;
    this.objectMapper = objectMapper;
  }

  @Override
  public void accept(OutboxMessage message) {
    if (message == null || !"automation.rider.assign_requested".equals(message.type())) {
      return;
    }
    DomainEvent event = parseEvent(message);
    if (event == null) {
      return;
    }
    Map<String, Object> payload = event.payload();
    UUID orderId = asUuid(payload.get("order_id"));
    if (orderId == null) {
      orderId = event.aggregateId();
    }
    if (orderId == null) {
      return;
    }
    dispatch.autoAssignOrder(orderId);
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
