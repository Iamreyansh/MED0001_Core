package com.nammamedmate.automation.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.RulesEngineService;
import com.nammamedmate.automation.application.RulesEngineService.EventPayload;
import com.nammamedmate.automation.application.WorkflowEngineService;
import com.nammamedmate.automation.domain.TriggerIdMapper;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxMessage;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Outbox/SQS → evaluate ACTIVE rules + start matching workflows. */
@Component
public class AutomationTriggerConsumer implements Consumer<OutboxMessage> {

  private final RulesEngineService engine;
  private final WorkflowEngineService workflows;
  private final ObjectMapper objectMapper;

  public AutomationTriggerConsumer(
      RulesEngineService engine, WorkflowEngineService workflows, ObjectMapper objectMapper) {
    this.engine = engine;
    this.workflows = workflows;
    this.objectMapper = objectMapper;
  }

  @Override
  public void accept(OutboxMessage message) {
    if (message == null || message.type() == null) {
      return;
    }
    Optional<String> trigger = TriggerIdMapper.fromEventType(message.type());
    if (trigger.isEmpty()) {
      return;
    }
    DomainEvent event = parse(message);
    Map<String, Object> payload = event == null ? Map.of() : event.payload();
    UUID entityId = entityId(event, payload);
    String entityType = entityType(event, payload);
    Instant firedAt = event == null ? null : event.occurredAt();
    engine.evaluateMatching(
        new EventPayload(trigger.get(), entityType, entityId, payload, firedAt));
    if (entityId != null) {
      Object name = payload.get("entity_name");
      if (name == null) {
        name = payload.get("order_number");
      }
      workflows.onTrigger(
          trigger.get(), entityType, entityId, name == null ? null : String.valueOf(name), payload);
    }
  }

  private DomainEvent parse(OutboxMessage message) {
    try {
      return objectMapper.readValue(message.payloadJson(), DomainEvent.class);
    } catch (Exception ex) {
      return null;
    }
  }

  private static UUID entityId(DomainEvent event, Map<String, Object> payload) {
    UUID fromPayload = asUuid(payload.get("order_id"));
    if (fromPayload == null) {
      fromPayload = asUuid(payload.get("entity_id"));
    }
    if (fromPayload == null) {
      fromPayload = asUuid(payload.get("ticket_id"));
    }
    if (fromPayload == null) {
      fromPayload = asUuid(payload.get("payment_id"));
    }
    if (fromPayload != null) {
      return fromPayload;
    }
    return event == null ? null : event.aggregateId();
  }

  private static String entityType(DomainEvent event, Map<String, Object> payload) {
    Object raw = payload.get("entity_type");
    if (raw != null && !String.valueOf(raw).isBlank()) {
      return String.valueOf(raw);
    }
    if (event != null && event.aggregateType() != null && !event.aggregateType().isBlank()) {
      return event.aggregateType().toUpperCase();
    }
    return "UNKNOWN";
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
