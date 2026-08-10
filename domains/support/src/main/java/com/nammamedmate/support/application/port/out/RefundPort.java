package com.nammamedmate.support.application.port.out;

import java.util.UUID;

/** Refund processing for approved disputes (stub or payment bridge). */
public interface RefundPort {

  record RefundResult(String transactionId, boolean processed) {}

  RefundResult processRefund(
      UUID orderId, UUID customerId, long amountPaise, String refundTo, UUID disputeId);
}
