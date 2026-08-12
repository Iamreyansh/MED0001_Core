package com.nammamedmate.automation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AutomationSimulation(
    UUID id,
    UUID ruleId,
    int sampleSize,
    int eventsScanned,
    int entitiesMatched,
    int conditionsFailedCount,
    FalsePositiveRisk falsePositiveRisk,
    String riskDetails,
    String estimatedImpactSummary,
    List<Map<String, Object>> actionsThatWouldFire,
    SimulationStatus status,
    Instant startedAt,
    Instant completedAt,
    UUID triggeredBy,
    Instant expiresAt) {

  public AutomationSimulation {
    actionsThatWouldFire =
        actionsThatWouldFire == null ? List.of() : List.copyOf(actionsThatWouldFire);
  }
}
