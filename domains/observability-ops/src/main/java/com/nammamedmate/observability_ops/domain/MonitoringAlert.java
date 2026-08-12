package com.nammamedmate.observability_ops.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MonitoringAlert(
    UUID id,
    AlertSeverity severity,
    AlertType type,
    String message,
    String triggeringMetric,
    BigDecimal triggeringValue,
    BigDecimal thresholdValue,
    UUID zoneId,
    Instant triggeredAt,
    boolean acknowledged,
    UUID acknowledgedBy,
    Instant acknowledgedAt,
    String acknowledgedNotes,
    boolean autoRemediated,
    Instant resolvedAt,
    String resolutionReason) {

  public boolean isOpen() {
    return resolvedAt == null;
  }
}
