package com.nammamedmate.order.domain;

public enum PaymentStatus {
  PENDING_COLLECTION,
  AWAITING_PAYMENT,
  PAID,
  COLLECTED,
  REFUNDED,
  PARTIALLY_REFUNDED
}
