package com.nammamedmate.automation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AutomationRule(
    UUID id,
    String name,
    String description,
    String triggerId,
    String triggerCategory,
    Map<String, Object> triggerParams,
    List<ConditionSpec> conditions,
    List<ActionSpec> actions,
    Guardrails guardrails,
    RuleStatus status,
    int fireCount,
    Instant lastFiredAt,
    boolean seedRule,
    int dedupWindowSeconds,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt) {

  public AutomationRule {
    triggerParams = triggerParams == null ? Map.of() : Map.copyOf(triggerParams);
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
    actions = actions == null ? List.of() : List.copyOf(actions);
    guardrails = guardrails == null ? Guardrails.NONE : guardrails;
    if (dedupWindowSeconds <= 0) {
      dedupWindowSeconds = 300;
    }
  }

  public RuleSnapshot toSnapshot() {
    return new RuleSnapshot(
        id, triggerId, conditions, actions, dedupWindowSeconds, status, guardrails);
  }
}
