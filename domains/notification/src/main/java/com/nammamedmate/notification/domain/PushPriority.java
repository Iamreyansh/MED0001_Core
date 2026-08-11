package com.nammamedmate.notification.domain;

public enum PushPriority {
  HIGH,
  NORMAL;

  public static PushPriority parseOrDefault(String raw) {
    if (raw == null || raw.isBlank()) {
      return NORMAL;
    }
    return PushPriority.valueOf(raw.trim().toUpperCase());
  }
}
