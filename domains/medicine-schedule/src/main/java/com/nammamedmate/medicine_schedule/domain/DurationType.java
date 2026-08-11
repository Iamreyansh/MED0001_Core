package com.nammamedmate.medicine_schedule.domain;

public enum DurationType {
  ONGOING,
  DAYS;

  public static DurationType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("duration_type is required");
    }
    try {
      return DurationType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid duration_type: " + raw);
    }
  }
}
