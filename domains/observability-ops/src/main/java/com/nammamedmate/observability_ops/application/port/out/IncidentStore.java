package com.nammamedmate.observability_ops.application.port.out;

import com.nammamedmate.observability_ops.domain.Incident;
import com.nammamedmate.observability_ops.domain.IncidentSeverity;
import com.nammamedmate.observability_ops.domain.IncidentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentStore {

  Optional<Incident> findById(UUID id);

  Optional<Incident> findBySourceAlertId(UUID alertId);

  Incident insert(Incident incident);

  Incident update(Incident incident);

  record Page(List<Incident> incidents, long total) {
    public Page {
      incidents = List.copyOf(incidents);
    }
  }

  Page list(
      IncidentStatus status,
      IncidentSeverity severity,
      Instant dateFrom,
      Instant dateTo,
      int page,
      int limit);

  int countP1P2Between(Instant fromInclusive, Instant toExclusive);

  List<Incident> findResolvedAwaitingPostmortemReminder(Instant resolvedBefore);
}
