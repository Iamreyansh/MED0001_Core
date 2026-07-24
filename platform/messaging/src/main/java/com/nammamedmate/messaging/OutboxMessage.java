package com.nammamedmate.messaging;

import java.time.Instant;
import java.util.UUID;

public record OutboxMessage(
    UUID id, String type, String payloadJson, Instant createdAt, boolean published) {

  public static OutboxMessage pending(String type, String payloadJson) {
    return new OutboxMessage(UUID.randomUUID(), type, payloadJson, Instant.now(), false);
  }

  public OutboxMessage markPublished() {
    return new OutboxMessage(id, type, payloadJson, createdAt, true);
  }
}
