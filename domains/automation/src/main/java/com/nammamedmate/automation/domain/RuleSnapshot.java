package com.nammamedmate.automation.domain;

import java.util.List;
import java.util.UUID;

/** Rule definition used by the evaluator / active cache. */
public record RuleSnapshot(
    UUID ruleId,
    String triggerId,
    List<ConditionSpec> conditions,
    List<ActionSpec> actions,
    int dedupWindowSeconds,
    RuleStatus status,
    Guardrails guardrails) {

  public RuleSnapshot {
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
    actions = actions == null ? List.of() : List.copyOf(actions);
    if (dedupWindowSeconds <= 0) {
      dedupWindowSeconds = 300;
    }
    status = status == null ? RuleStatus.ACTIVE : status;
    guardrails = guardrails == null ? Guardrails.NONE : guardrails;
  }

  /** Convenience for tests / evaluate overrides (ACTIVE, no guardrails). */
  public RuleSnapshot(
      UUID ruleId,
      String triggerId,
      List<ConditionSpec> conditions,
      List<ActionSpec> actions,
      int dedupWindowSeconds) {
    this(
        ruleId,
        triggerId,
        conditions,
        actions,
        dedupWindowSeconds,
        RuleStatus.ACTIVE,
        Guardrails.NONE);
  }
}
