package com.nammamedmate.notification.domain;

public enum SmsCategory {
  OTP,
  TRANSACTIONAL,
  PROMOTIONAL;

  public static SmsCategory parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_CATEGORY");
    }
    try {
      return SmsCategory.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_CATEGORY");
    }
  }
}
