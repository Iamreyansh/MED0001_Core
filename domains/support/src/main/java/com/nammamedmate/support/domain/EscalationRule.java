package com.nammamedmate.support.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EscalationRule(
    UUID id,
    SlaLevel level,
    String criteria,
    String assignedTeam,
    List<String> notificationChannels,
    int autoEscalateAfterMinutes,
    UUID updatedBy,
    Instant updatedAt) {

  public EscalationRule {
    notificationChannels =
        notificationChannels == null ? List.of() : List.copyOf(notificationChannels);
  }
}
