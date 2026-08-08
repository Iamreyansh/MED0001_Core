package com.nammamedmate.rider.application.port.out;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface AssignmentOtpCachePort {

  Duration TTL = Duration.ofMinutes(30);

  void storePickupOtp(UUID orderId, String otp);

  void storeDeliveryOtp(UUID orderId, String otp);

  Optional<String> getPickupOtp(UUID orderId);

  Optional<String> getDeliveryOtp(UUID orderId);

  void evict(UUID orderId);

  int remainingPickupAttempts(UUID orderId);

  /** Decrement attempt counter; returns remaining after decrement. */
  int consumePickupAttempt(UUID orderId);

  void resetPickupAttempts(UUID orderId);

  int getConcurrent(UUID riderId);

  void setConcurrent(UUID riderId, int value);

  void incrConcurrent(UUID riderId);

  void decrConcurrent(UUID riderId);
}
