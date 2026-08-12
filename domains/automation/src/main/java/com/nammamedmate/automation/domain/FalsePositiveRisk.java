package com.nammamedmate.automation.domain;

import java.util.Locale;

public enum FalsePositiveRisk {
  LOW,
  MEDIUM,
  HIGH;

  public static FalsePositiveRisk parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return FalsePositiveRisk.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
