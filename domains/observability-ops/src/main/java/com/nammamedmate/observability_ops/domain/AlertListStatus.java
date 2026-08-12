package com.nammamedmate.observability_ops.domain;

public enum AlertListStatus {
  ACTIVE,
  ACKNOWLEDGED,
  RESOLVED;

  public static AlertListStatus from(String raw) {
    if (raw == null || raw.isBlank()) {
      return ACTIVE;
    }
    try {
      return AlertListStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return ACTIVE;
    }
  }
}
