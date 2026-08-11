package com.nammamedmate.notification.domain;

public enum PreferenceEntityType {
  CUSTOMER,
  PHARMACY;

  public static PreferenceEntityType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_ENTITY_TYPE");
    }
    try {
      return PreferenceEntityType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_ENTITY_TYPE");
    }
  }
}
