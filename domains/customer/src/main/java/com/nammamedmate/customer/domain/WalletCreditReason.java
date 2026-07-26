package com.nammamedmate.customer.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;

public enum WalletCreditReason {
  REFUND,
  GOODWILL,
  PROMOTIONAL,
  /** System-only referral reward credit (not accepted on admin credit API). */
  REFERRAL;

  private static final String ADMIN_REASONS = "REFUND, GOODWILL, PROMOTIONAL";

  /** Admin credit reasons only — REFERRAL is system-disbursed. */
  public static WalletCreditReason require(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason must be one of: " + ADMIN_REASONS, 400);
    }
    WalletCreditReason reason;
    try {
      reason = WalletCreditReason.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "reason must be one of: " + ADMIN_REASONS, 400);
    }
    if (reason == REFERRAL) {
      throw new AppException("VALIDATION_ERROR", "reason must be one of: " + ADMIN_REASONS, 400);
    }
    return reason;
  }
}
