package com.nammamedmate.medicine_schedule.domain;

public enum DoseLogStatus {
  UPCOMING,
  TAKEN,
  SKIPPED,
  MISSED;

  public static DoseLogStatus parseMarkStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_STATUS");
    }
    String v = raw.trim().toUpperCase();
    if ("TAKEN".equals(v) || "SKIPPED".equals(v)) {
      return DoseLogStatus.valueOf(v);
    }
    throw new IllegalArgumentException("INVALID_STATUS");
  }
}
