package com.nammamedmate.automation.domain;

public enum KillSwitchStatus {
  ACTIVE,
  PAUSED;

  public static KillSwitchStatus from(String raw) {
    if (raw == null || raw.isBlank()) {
      return ACTIVE;
    }
    return "PAUSED".equalsIgnoreCase(raw.trim()) ? PAUSED : ACTIVE;
  }
}
