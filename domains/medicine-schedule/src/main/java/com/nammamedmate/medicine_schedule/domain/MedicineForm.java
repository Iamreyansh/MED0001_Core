package com.nammamedmate.medicine_schedule.domain;

public enum MedicineForm {
  TABLET,
  SYRUP,
  CAPSULE,
  DROPS,
  INJECTION,
  OTHER;

  public static MedicineForm parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("form is required");
    }
    try {
      return MedicineForm.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid form: " + raw);
    }
  }
}
