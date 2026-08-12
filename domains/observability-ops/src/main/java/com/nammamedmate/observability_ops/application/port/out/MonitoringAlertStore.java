package com.nammamedmate.observability_ops.application.port.out;

import com.nammamedmate.observability_ops.domain.AlertListStatus;
import com.nammamedmate.observability_ops.domain.AlertSeverity;
import com.nammamedmate.observability_ops.domain.AlertType;
import com.nammamedmate.observability_ops.domain.MonitoringAlert;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonitoringAlertStore {

  Optional<MonitoringAlert> findById(UUID id);

  Optional<MonitoringAlert> findOpen(AlertType type, UUID zoneId);

  List<MonitoringAlert> findOpen();

  MonitoringAlert insert(MonitoringAlert alert);

  void updateTriggeredAt(UUID id, Instant triggeredAt);

  void acknowledge(UUID id, UUID by, Instant at, String notes);

  void resolve(UUID id, Instant resolvedAt, String reason);

  void markAutoRemediated(UUID id, boolean value);

  record Page(List<MonitoringAlert> alerts, long total) {
    public Page {
      alerts = List.copyOf(alerts);
    }
  }

  Page list(AlertListStatus status, AlertSeverity severity, int page, int limit);

  int purgeOlderThan(Instant cutoff);
}
