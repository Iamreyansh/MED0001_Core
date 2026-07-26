package com.nammamedmate.customer.domain;

import java.util.Locale;

public enum NotifyChannel {
  PUSH,
  SMS,
  BOTH;

  public static NotifyChannel parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("channel is required");
    }
    try {
      return NotifyChannel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid channel: " + raw);
    }
  }
}
