package com.nammamedmate.order.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Short-lived plaintext pickup OTP for pharmacy→rider handoff. Never the customer delivery OTP. */
public interface PickupOtpCachePort {

  void store(UUID orderId, String otp);

  Optional<String> get(UUID orderId);
}
