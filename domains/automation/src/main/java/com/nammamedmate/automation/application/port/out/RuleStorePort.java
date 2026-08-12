package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.AutomationRule;
import com.nammamedmate.automation.domain.RuleSnapshot;
import com.nammamedmate.automation.domain.RuleStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleStorePort {

  Optional<AutomationRule> findById(UUID id);

  Optional<AutomationRule> findByNameIgnoreCase(String name);

  List<RuleSnapshot> listActiveOrSimulating();

  long countByStatus(RuleStatus status);

  long countFiltered(String status, String triggerCategory, String search);

  List<AutomationRule> listFiltered(
      String status, String triggerCategory, String search, int offset, int limit);

  void insert(AutomationRule rule);

  void update(AutomationRule rule);

  void softDelete(UUID id, Instant deletedAt);

  void recordFire(UUID id, Instant firedAt);

  void markSimulatingStarted(UUID id, Instant startedAt);

  void clearSimulatingStarted(UUID id);

  /** Rules stuck in SIMULATING since before {@code cutoff}. */
  List<UUID> listSimulatingStartedBefore(Instant cutoff, int limit);
}
