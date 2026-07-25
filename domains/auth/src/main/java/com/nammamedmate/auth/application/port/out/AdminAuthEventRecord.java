package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AdminAuthEventRecord(
    UUID id,
    UUID adminId,
    String eventType,
    String ipAddress,
    String userAgent,
    Map<String, Object> metadata,
    Instant createdAt) {

  public AdminAuthEventRecord {
    metadata = metadata == null ? null : Map.copyOf(metadata);
  }
}
