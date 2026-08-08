package com.nammamedmate.order.domain;

public enum PaymentMethod {
  UPI,
  CARD,
  COD,
  WALLET;

  public boolean isOnline() {
    return this == UPI || this == CARD;
  }
}
