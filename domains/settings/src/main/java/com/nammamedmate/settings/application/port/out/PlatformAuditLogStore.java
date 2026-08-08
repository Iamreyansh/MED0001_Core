package com.nammamedmate.settings.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PlatformAuditLogStore {

  record AuditLogRow(
      UUID id,
      UUID actorId,
      String actorName,
      String actorRole,
      String actorType,
      String action,
      String resourceType,
      UUID resourceId,
      Map<String, Object> beforeState,
      Map<String, Object> afterState,
      Map<String, Object> metadata,
      String ipAddress,
      String userAgent,
      Instant timestamp) {
    public AuditLogRow {
      beforeState = beforeState == null ? null : Map.copyOf(beforeState);
      afterState = afterState == null ? null : Map.copyOf(afterState);
      metadata = metadata == null ? null : Map.copyOf(metadata);
    }
  }

  record ListFilter(
      UUID actorId,
      String actorType,
      String resourceType,
      UUID resourceId,
      String action,
      Instant from,
      Instant to,
      String sort,
      String order,
      int limit,
      int offset) {}

  record PageResult(List<AuditLogRow> rows, long total) {
    public PageResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  void append(
      UUID id,
      UUID actorId,
      String actorName,
      String actorRole,
      String actorType,
      String action,
      String resourceType,
      UUID resourceId,
      Map<String, Object> beforeState,
      Map<String, Object> afterState,
      Map<String, Object> metadata,
      String ipAddress,
      String userAgent,
      Instant timestamp);

  PageResult list(ListFilter filter);

  Optional<AuditLogRow> findById(UUID id);

  List<AuditLogRow> listForArchive(Instant olderThan, int limit);

  void markArchived(UUID id, Instant archivedAt);
}
