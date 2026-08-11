package com.nammamedmate.notification.domain;

public enum EmailBounceType {
  HARD,
  SOFT;

  public static EmailBounceType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_BOUNCE_TYPE");
    }
    try {
      return EmailBounceType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_BOUNCE_TYPE");
    }
  }
}
