package com.nammamedmate.notification.domain;

public enum EmailLogStatus {
  SENT,
  DELIVERED,
  OPENED,
  CLICKED,
  BOUNCED,
  SPAM;

  public static EmailLogStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_STATUS");
    }
    try {
      return EmailLogStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_STATUS");
    }
  }
}
