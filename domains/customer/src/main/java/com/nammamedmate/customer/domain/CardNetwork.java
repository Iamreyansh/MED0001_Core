package com.nammamedmate.customer.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;

public enum CardNetwork {
  VISA,
  MASTERCARD,
  RUPAY,
  AMEX,
  MAESTRO,
  DINERS;

  public static CardNetwork parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "card_network is required", 400);
    }
    try {
      return CardNetwork.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException(
          "VALIDATION_ERROR",
          "card_network must be one of: VISA, MASTERCARD, RUPAY, AMEX, MAESTRO, DINERS",
          400);
    }
  }
}
