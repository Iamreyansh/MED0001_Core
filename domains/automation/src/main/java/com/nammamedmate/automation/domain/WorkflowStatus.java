package com.nammamedmate.automation.domain;

import java.util.Locale;

public enum WorkflowStatus {
  ACTIVE,
  INACTIVE;

  public static WorkflowStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return WorkflowStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
