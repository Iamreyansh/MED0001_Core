package com.nammamedmate.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DomainEvent(
    UUID eventId,
    String type,
    String aggregateType,
    UUID aggregateId,
    Instant occurredAt,
    Map<String, Object> payload) {

  public DomainEvent {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  public static DomainEvent of(
      String type, String aggregateType, UUID aggregateId, Map<String, Object> payload) {
    return new DomainEvent(
        UUID.randomUUID(), type, aggregateType, aggregateId, Instant.now(), payload);
  }
}
