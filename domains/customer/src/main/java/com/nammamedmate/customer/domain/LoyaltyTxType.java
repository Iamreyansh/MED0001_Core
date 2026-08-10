package com.nammamedmate.customer.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;

/** EPIC-002 EARN/REVERSE kept; EPIC-013 adds REDEEM/EXPIRE/ADJUST. */
public enum LoyaltyTxType {
  EARN,
  REVERSE,
  REDEEM,
  EXPIRE,
  ADJUST;

  public static LoyaltyTxType parseOptional(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LoyaltyTxType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException(
          "VALIDATION_ERROR", "type must be one of: EARN, REVERSE, REDEEM, EXPIRE, ADJUST", 400);
    }
  }
}
