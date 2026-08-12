package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.ActivityLogEntry;
import com.nammamedmate.automation.domain.ActivityStats;
import com.nammamedmate.automation.domain.RuleHealthMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Append-only activity log for action outcomes (failures included). */
public interface ActivityLogPort {

  UUID append(String actionType, String status, String message, Map<String, Object> detail);

  Optional<ActivityLogEntry> findById(UUID id);

  boolean existsRollbackFor(UUID originalId);

  List<ActivityLogEntry> list(ActivityQuery query, int offset, int limit);

  long count(ActivityQuery query);

  ActivityStats stats(Instant now);

  List<RuleHealthMetrics> perRuleHealth(Instant since);

  record ActivityQuery(
      String status,
      UUID ruleId,
      String triggerCategory,
      String entityType,
      Instant dateFrom,
      Instant dateTo,
      Set<String> actionTypesOnly) {

    public ActivityQuery {
      actionTypesOnly = actionTypesOnly == null ? null : Set.copyOf(actionTypesOnly);
    }
  }
}
