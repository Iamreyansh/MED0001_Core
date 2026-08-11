package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record PushBroadcast(
    UUID id,
    BroadcastAudience audience,
    String title,
    String body,
    Map<String, Object> data,
    Instant scheduleAt,
    BroadcastStatus status,
    int estimatedRecipients,
    UUID createdBy,
    Instant createdAt,
    Instant executedAt) {
  public PushBroadcast {
    data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }
}
