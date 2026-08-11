package com.nammamedmate.notification.domain;

public enum InAppNotificationType {
  ORDER_UPDATE,
  PROMO,
  REFILL_REMINDER,
  SYSTEM;

  public static InAppNotificationType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_TYPE");
    }
    try {
      return InAppNotificationType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_TYPE");
    }
  }

  public boolean canDelete() {
    return this == PROMO || this == SYSTEM;
  }

  public int retentionDays() {
    return this == ORDER_UPDATE ? 90 : 30;
  }
}
