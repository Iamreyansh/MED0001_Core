package com.nammamedmate.automation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WorkflowExecution(
    UUID id,
    UUID workflowId,
    int workflowVersion,
    String entityType,
    UUID entityId,
    String entityName,
    String currentStepId,
    WorkflowExecutionStatus status,
    Instant waitUntil,
    Map<String, Object> context,
    Instant startedAt,
    Instant completedAt,
    Instant lastStepExecutedAt,
    List<Map<String, Object>> stepHistory) {

  public WorkflowExecution {
    context = context == null ? Map.of() : Map.copyOf(context);
    stepHistory = stepHistory == null ? List.of() : List.copyOf(stepHistory);
  }
}
