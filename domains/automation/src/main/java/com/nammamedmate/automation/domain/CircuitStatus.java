package com.nammamedmate.automation.domain;

import java.util.Locale;

public enum CircuitStatus {
  CLOSED,
  OPEN;

  public static CircuitStatus from(String raw) {
    if (raw == null || raw.isBlank()) {
      return CLOSED;
    }
    return "OPEN".equalsIgnoreCase(raw.trim()) ? OPEN : CLOSED;
  }

  public static CircuitStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return CLOSED;
    }
    try {
      return CircuitStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return CLOSED;
    }
  }
}
