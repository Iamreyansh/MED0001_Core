package com.nammamedmate.notification.domain;

public enum BroadcastAudience {
  ALL_CUSTOMERS,
  ALL_PHARMACIES,
  ALL_RIDERS;

  public static BroadcastAudience parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_AUDIENCE");
    }
    try {
      return BroadcastAudience.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_AUDIENCE");
    }
  }

  public NotificationUserType toUserType() {
    return switch (this) {
      case ALL_CUSTOMERS -> NotificationUserType.CUSTOMER;
      case ALL_PHARMACIES -> NotificationUserType.PHARMACY_STAFF;
      case ALL_RIDERS -> NotificationUserType.RIDER;
    };
  }
}
