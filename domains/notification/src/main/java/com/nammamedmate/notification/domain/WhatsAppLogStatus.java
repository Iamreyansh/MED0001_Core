package com.nammamedmate.notification.domain;

public enum WhatsAppLogStatus {
  SENT,
  DELIVERED,
  READ,
  FAILED;

  public static WhatsAppLogStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_STATUS");
    }
    try {
      return WhatsAppLogStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_STATUS");
    }
  }
}
