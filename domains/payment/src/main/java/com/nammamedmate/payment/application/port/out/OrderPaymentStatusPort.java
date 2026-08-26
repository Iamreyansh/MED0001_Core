package com.nammamedmate.payment.application.port.out;

import java.util.UUID;

/** Advances order workflow on payment capture / failure (bridged to order domain in apps/api). */
public interface OrderPaymentStatusPort {

  void onCaptured(UUID orderId, String gatewayPaymentId);

  void onFailed(UUID orderId, String reason);
}
