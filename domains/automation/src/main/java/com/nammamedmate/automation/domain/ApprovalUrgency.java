package com.nammamedmate.automation.domain;

import java.util.Locale;

public enum ApprovalUrgency {
  URGENT,
  NORMAL;

  public static ApprovalUrgency parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("urgency required");
    }
    return ApprovalUrgency.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
