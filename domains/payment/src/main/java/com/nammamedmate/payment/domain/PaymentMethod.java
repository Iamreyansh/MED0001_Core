package com.nammamedmate.payment.domain;

public enum PaymentMethod {
  UPI,
  CARD,
  COD,
  WALLET_ONLY;

  public boolean isOnline() {
    return this == UPI || this == CARD;
  }

  public static PaymentMethod fromOrderMethod(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("payment method required");
    }
    String n = raw.trim().toUpperCase();
    if ("WALLET".equals(n)) {
      return WALLET_ONLY;
    }
    return PaymentMethod.valueOf(n);
  }
}
