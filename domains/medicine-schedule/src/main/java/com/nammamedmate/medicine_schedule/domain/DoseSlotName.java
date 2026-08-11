package com.nammamedmate.medicine_schedule.domain;

public enum DoseSlotName {
  MORNING,
  AFTERNOON,
  EVENING,
  NIGHT,
  CUSTOM;

  public static DoseSlotName parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("slot is required");
    }
    try {
      return DoseSlotName.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid dose slot: " + raw);
    }
  }
}
