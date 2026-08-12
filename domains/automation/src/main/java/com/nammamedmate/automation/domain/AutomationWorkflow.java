package com.nammamedmate.automation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AutomationWorkflow(
    UUID id,
    String name,
    String description,
    String triggerId,
    List<WorkflowStep> steps,
    WorkflowStatus status,
    int version,
    boolean seedWorkflow,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt) {

  public AutomationWorkflow {
    steps = steps == null ? List.of() : List.copyOf(steps);
  }

  public WorkflowStep stepById(String stepId) {
    if (stepId == null) {
      return null;
    }
    for (WorkflowStep s : steps) {
      if (stepId.equals(s.stepId())) {
        return s;
      }
    }
    return null;
  }

  public WorkflowStep entryStep() {
    return steps.isEmpty() ? null : steps.getFirst();
  }
}
