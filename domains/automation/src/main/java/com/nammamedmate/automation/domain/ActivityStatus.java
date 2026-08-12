package com.nammamedmate.automation.domain;

import java.util.Locale;

public enum ActivityStatus {
  EXECUTED,
  SIMULATED,
  PENDING_APPROVAL,
  APPROVED,
  REJECTED,
  ROLLED_BACK,
  RATE_LIMITED,
  DUPLICATE_SKIPPED,
  EXCEPTION,
  KILL_SWITCH_PAUSED;

  public static ActivityStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("status required");
    }
    return ActivityStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }

  /** Writer-side coerce: engine/stub aliases → persisted status. */
  public static ActivityStatus fromLog(String raw) {
    if (raw == null || raw.isBlank()) {
      return EXCEPTION;
    }
    String u = raw.trim().toUpperCase(Locale.ROOT);
    if ("FAILED".equals(u)) {
      return EXCEPTION;
    }
    if ("DISPATCHED".equals(u)) {
      return EXECUTED;
    }
    if ("DUPLICATE_EXECUTION_SKIPPED".equals(u)) {
      return DUPLICATE_SKIPPED;
    }
    try {
      return ActivityStatus.valueOf(u);
    } catch (IllegalArgumentException ex) {
      return EXCEPTION;
    }
  }
}
