package com.nammamedmate.customer.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;

public enum CardType {
  CREDIT,
  DEBIT,
  PREPAID;

  public static CardType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "card_type is required", 400);
    }
    try {
      return CardType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException(
          "VALIDATION_ERROR", "card_type must be one of: CREDIT, DEBIT, PREPAID", 400);
    }
  }
}
