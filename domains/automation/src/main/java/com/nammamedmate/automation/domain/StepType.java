package com.nammamedmate.automation.domain;

import java.util.Locale;

public enum StepType {
  ACTION,
  WAIT,
  BRANCH;

  public static StepType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return StepType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
