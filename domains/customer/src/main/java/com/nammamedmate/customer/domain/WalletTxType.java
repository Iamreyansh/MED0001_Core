package com.nammamedmate.customer.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;

public enum WalletTxType {
  CREDIT,
  DEBIT,
  EXPIRED;

  public static WalletTxType parseOptional(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return WalletTxType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException(
          "VALIDATION_ERROR", "type must be one of: CREDIT, DEBIT, EXPIRED", 400);
    }
  }
}
