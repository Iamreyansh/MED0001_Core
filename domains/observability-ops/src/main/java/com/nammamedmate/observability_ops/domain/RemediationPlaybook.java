package com.nammamedmate.observability_ops.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RemediationPlaybook(
    UUID id,
    AlertType alertType,
    RemediationActionType autoRemediationAction,
    String description,
    Map<String, Object> threshold,
    boolean enabled,
    Instant lastTriggeredAt,
    UUID updatedBy,
    Instant updatedAt) {

  public RemediationPlaybook {
    threshold = threshold == null ? Map.of() : Map.copyOf(threshold);
  }
}
