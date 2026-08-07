package com.nammamedmate.catalogue.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface AuditLogStore {

  record AuditLogRecord(
      UUID id,
      String entityType,
      UUID entityId,
      String action,
      UUID actorId,
      String actorRole,
      Map<String, Object> payload,
      String ipAddress,
      Instant createdAt) {
    public AuditLogRecord {
      payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
  }

  void append(AuditLogRecord record);
}
