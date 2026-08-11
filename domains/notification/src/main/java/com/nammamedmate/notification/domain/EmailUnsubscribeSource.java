package com.nammamedmate.notification.domain;

public enum EmailUnsubscribeSource {
  LINK_CLICK,
  SPAM_REPORT,
  MANUAL;

  public static EmailUnsubscribeSource parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_UNSUBSCRIBE_SOURCE");
    }
    try {
      return EmailUnsubscribeSource.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_UNSUBSCRIBE_SOURCE");
    }
  }
}
