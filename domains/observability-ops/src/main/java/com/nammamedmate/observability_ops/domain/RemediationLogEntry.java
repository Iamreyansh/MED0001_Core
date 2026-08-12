package com.nammamedmate.observability_ops.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RemediationLogEntry(
    UUID id,
    UUID alertId,
    UUID playbookId,
    RemediationActionType actionType,
    RemediationTriggerType triggerType,
    String targetEntityType,
    UUID targetEntityId,
    Map<String, Object> actionDetails,
    RemediationStatus status,
    UUID triggeredBy,
    Instant triggeredAt,
    Instant completedAt,
    String errorMessage) {

  public RemediationLogEntry {
    actionDetails = actionDetails == null ? Map.of() : Map.copyOf(actionDetails);
  }
}
