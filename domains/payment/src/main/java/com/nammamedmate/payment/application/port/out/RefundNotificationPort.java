package com.nammamedmate.payment.application.port.out;

import java.util.UUID;

/** Customer push/SMS on refund completion (bridged to outbox in apps/api). */
public interface RefundNotificationPort {

  void refundCompleted(UUID customerId, UUID refundId, UUID orderId, long amountPaise);
}
