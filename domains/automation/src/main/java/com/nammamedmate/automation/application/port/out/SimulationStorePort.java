package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.AutomationSimulation;
import com.nammamedmate.automation.domain.FalsePositiveRisk;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SimulationStorePort {

  void insert(AutomationSimulation simulation);

  Optional<AutomationSimulation> findById(UUID id);

  Optional<AutomationSimulation> findByRuleAndId(UUID ruleId, UUID simulationId);

  Optional<AutomationSimulation> findLatestCompletedByRuleId(UUID ruleId);

  List<UUID> listRunning(int limit);

  void markCompleted(
      UUID id,
      int eventsScanned,
      int entitiesMatched,
      int conditionsFailedCount,
      FalsePositiveRisk risk,
      String riskDetails,
      String impactSummary,
      List<Map<String, Object>> actionsThatWouldFire,
      Instant completedAt,
      Instant expiresAt);

  void markFailed(UUID id, Instant completedAt, String message);

  int deleteExpired(Instant now);
}
