package com.nammamedmate.order.domain;

/** Order lifecycle (STORY-004 placement + STORY-005 forward-compat). */
public enum OrderStatus {
  PAYMENT_PENDING,
  PENDING_ACCEPTANCE,
  ACCEPTED,
  PACKING,
  READY_FOR_PICKUP,
  OUT_FOR_DELIVERY,
  DELIVERED,
  CANCELLED;

  public boolean isTerminal() {
    return this == DELIVERED || this == CANCELLED;
  }
}
