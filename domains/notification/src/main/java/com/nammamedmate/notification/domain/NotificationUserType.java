package com.nammamedmate.notification.domain;

public enum NotificationUserType {
  CUSTOMER,
  PHARMACY_STAFF,
  RIDER;

  public static NotificationUserType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("recipient_type required");
    }
    try {
      return NotificationUserType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_RECIPIENT_TYPE");
    }
  }
}
