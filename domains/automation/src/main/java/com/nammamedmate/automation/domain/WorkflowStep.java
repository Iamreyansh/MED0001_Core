package com.nammamedmate.automation.domain;

import java.util.Map;

public record WorkflowStep(
    String stepId,
    StepType type,
    String actionId,
    Map<String, Object> params,
    Integer waitDurationHours,
    ConditionSpec condition,
    String nextStepIdOnTrue,
    String nextStepIdOnFalse) {

  public WorkflowStep {
    params = params == null ? Map.of() : Map.copyOf(params);
  }
}
