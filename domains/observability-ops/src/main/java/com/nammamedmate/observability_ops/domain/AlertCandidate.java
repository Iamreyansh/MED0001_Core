package com.nammamedmate.observability_ops.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Candidate alert produced by evaluation before persistence. */
public record AlertCandidate(
    AlertSeverity severity,
    AlertType type,
    String message,
    String triggeringMetric,
    BigDecimal triggeringValue,
    BigDecimal thresholdValue,
    UUID zoneId,
    boolean healthy) {

  public static AlertCandidate firing(
      AlertSeverity severity,
      AlertType type,
      String message,
      String metric,
      BigDecimal value,
      BigDecimal threshold,
      UUID zoneId) {
    return new AlertCandidate(severity, type, message, metric, value, threshold, zoneId, false);
  }

  public static AlertCandidate healthy(AlertType type, UUID zoneId, String metric) {
    return new AlertCandidate(null, type, null, metric, null, null, zoneId, true);
  }
}
