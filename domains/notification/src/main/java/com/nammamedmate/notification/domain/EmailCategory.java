package com.nammamedmate.notification.domain;

public enum EmailCategory {
  TRANSACTIONAL,
  LIFECYCLE,
  MARKETING;

  public static EmailCategory parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_CATEGORY");
    }
    try {
      return EmailCategory.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_CATEGORY");
    }
  }

  public boolean isTransactional() {
    return this == TRANSACTIONAL;
  }

  public boolean requiresUnsubscribeLink() {
    return this == MARKETING || this == LIFECYCLE;
  }
}
