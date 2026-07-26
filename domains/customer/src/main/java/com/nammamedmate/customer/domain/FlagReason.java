package com.nammamedmate.customer.domain;

import java.util.Locale;

public enum FlagReason {
  HIGH_CANCELLATION,
  FRAUD_SUSPICION,
  ABUSIVE_BEHAVIOUR,
  DUPLICATE_ACCOUNT,
  PAYMENT_DEFAULT,
  OTHER;

  public static FlagReason parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("reason is required");
    }
    try {
      return FlagReason.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid flag reason: " + raw);
    }
  }
}
