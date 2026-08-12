package com.nammamedmate.observability_ops.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Incident(
    UUID id,
    String incidentNumber,
    String title,
    IncidentSeverity severity,
    String description,
    IncidentStatus status,
    List<AffectedService> affectedServices,
    Map<String, Object> impactedMetrics,
    long impactedGmvPaise,
    String rootCause,
    String fixApplied,
    String preventionSteps,
    boolean postmortemFiled,
    Instant postmortemDeadline,
    Instant postmortemReminderSentAt,
    Instant detectedAt,
    Instant resolvedAt,
    Integer durationMinutes,
    UUID createdBy,
    UUID sourceAlertId,
    List<IncidentStatusEntry> statusHistory) {

  public Incident {
    affectedServices = List.copyOf(affectedServices);
    impactedMetrics = Map.copyOf(impactedMetrics);
    statusHistory = List.copyOf(statusHistory);
  }

  public boolean isResolved() {
    return status == IncidentStatus.RESOLVED;
  }

  public boolean postmortemRequired() {
    return severity == IncidentSeverity.P1 || severity == IncidentSeverity.P2;
  }
}
