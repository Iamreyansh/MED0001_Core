package com.nammamedmate.customer.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;

public enum WalletCreditReason {
  REFUND,
  GOODWILL,
  PROMOTIONAL;

  public static WalletCreditReason require(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException(
          "VALIDATION_ERROR", "reason must be one of: REFUND, GOODWILL, PROMOTIONAL", 400);
    }
    try {
      return WalletCreditReason.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException(
          "VALIDATION_ERROR", "reason must be one of: REFUND, GOODWILL, PROMOTIONAL", 400);
    }
  }
}
