package com.nammamedmate.notification.domain;

public enum PreferenceChangeSource {
  USER,
  UNSUBSCRIBE_LINK,
  SPAM_REPORT,
  SYSTEM;

  public static PreferenceChangeSource parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_CHANGE_SOURCE");
    }
    try {
      return PreferenceChangeSource.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_CHANGE_SOURCE");
    }
  }
}
