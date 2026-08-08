package com.nammamedmate.order.application.port.out;

import java.util.UUID;

/** Short-lived plaintext delivery OTP for SMS worker; never written to outbox. */
@FunctionalInterface
public interface DeliveryOtpCachePort {

  void store(UUID orderId, String otp);
}
