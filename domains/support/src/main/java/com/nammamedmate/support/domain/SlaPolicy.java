package com.nammamedmate.support.domain;

import java.time.Instant;
import java.util.UUID;

public record SlaPolicy(
    UUID id,
    String category,
    String priority,
    int firstResponseSlaMinutes,
    int resolutionSlaMinutes,
    SlaLevel slaLevel,
    UUID updatedBy,
    Instant updatedAt,
    Instant createdAt) {

  public int resolutionSlaHours() {
    return resolutionSlaMinutes / 60;
  }
}
