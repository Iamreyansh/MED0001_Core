package com.nammamedmate.automation.domain;

import java.util.Locale;

public enum WorkflowExecutionStatus {
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELLED,
  PAUSED;

  public static WorkflowExecutionStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return WorkflowExecutionStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
