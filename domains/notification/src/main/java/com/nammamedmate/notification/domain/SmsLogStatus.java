package com.nammamedmate.notification.domain;

public enum SmsLogStatus {
  SENT,
  DELIVERED,
  FAILED,
  EXPIRED,
  SKIPPED_DND;

  public static SmsLogStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_STATUS");
    }
    try {
      return SmsLogStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_STATUS");
    }
  }
}
