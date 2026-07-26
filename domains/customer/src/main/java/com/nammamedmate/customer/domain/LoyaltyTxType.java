package com.nammamedmate.customer.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;

public enum LoyaltyTxType {
  EARN,
  REVERSE;

  public static LoyaltyTxType parseOptional(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LoyaltyTxType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "type must be one of: EARN, REVERSE", 400);
    }
  }
}
