package com.nammamedmate.observability_ops.application.port.out;

import com.nammamedmate.observability_ops.domain.RemediationActionType;
import com.nammamedmate.observability_ops.domain.RemediationLogEntry;
import com.nammamedmate.observability_ops.domain.RemediationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface RemediationLogStore {

  RemediationLogEntry insert(RemediationLogEntry entry);

  void complete(
      UUID id,
      RemediationStatus status,
      Map<String, Object> actionDetails,
      Instant completedAt,
      String errorMessage);

  Optional<Instant> lastTriggeredAt(RemediationActionType actionType, UUID targetEntityId);

  int countByActionAndTargetSince(
      RemediationActionType actionType, UUID targetEntityId, Instant since);

  record Page(List<RemediationLogEntry> entries, long total) {
    public Page {
      entries = List.copyOf(entries);
    }
  }

  Page list(
      RemediationActionType actionType,
      RemediationStatus status,
      Instant dateFrom,
      Instant dateTo,
      int page,
      int limit);
}
