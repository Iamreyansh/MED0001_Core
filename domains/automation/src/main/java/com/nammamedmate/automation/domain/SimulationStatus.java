package com.nammamedmate.automation.domain;

import java.util.Locale;

public enum SimulationStatus {
  RUNNING,
  COMPLETED,
  FAILED;

  public static SimulationStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return SimulationStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
