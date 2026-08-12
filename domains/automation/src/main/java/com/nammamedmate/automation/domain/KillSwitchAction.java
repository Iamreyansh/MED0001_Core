package com.nammamedmate.automation.domain;

import java.util.Locale;

public enum KillSwitchAction {
  PAUSE,
  RESUME;

  public KillSwitchStatus toStatus() {
    return this == PAUSE ? KillSwitchStatus.PAUSED : KillSwitchStatus.ACTIVE;
  }

  public static KillSwitchAction parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("action required");
    }
    return KillSwitchAction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
