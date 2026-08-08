package com.nammamedmate.settings.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AuditExportJobStore {

  record ExportJobRow(
      UUID id, String status, Map<String, Object> filters, String downloadUrl, Instant createdAt) {
    public ExportJobRow {
      filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
  }

  void insertQueued(UUID id, Map<String, Object> filters, Instant createdAt);

  void markCompleted(UUID id, String downloadUrl, Instant completedAt);

  Optional<ExportJobRow> findById(UUID id);
}
