package com.nammamedmate.automation.domain;

import java.util.Locale;

public enum RuleStatus {
  ACTIVE,
  INACTIVE,
  SIMULATING;

  public static RuleStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return RuleStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
