package com.nammamedmate.automation.domain;

import java.util.Locale;

public enum ApprovalStatus {
  PENDING,
  APPROVED,
  REJECTED,
  EXPIRED;

  public static ApprovalStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("status required");
    }
    return ApprovalStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
