package com.nammamedmate.rider.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cross-domain orders/pharmacies/customers — JDBC bridge in apps/api; stub in RiderConfig. */
public interface DispatchOrderPort {

  record QueueOrder(
      UUID orderId,
      String orderNumber,
      UUID pharmacyId,
      String pharmacyName,
      UUID zoneId,
      String zoneName,
      int itemsCount,
      long orderValuePaise,
      String paymentMethod,
      Instant createdAt,
      Instant readyForPickupAt,
      Double pharmacyLat,
      Double pharmacyLng) {}

  record OrderDetails(
      UUID orderId,
      String orderNumber,
      String status,
      UUID riderId,
      UUID pharmacyId,
      String pharmacyName,
      String pharmacyAddress,
      Double pharmacyLat,
      Double pharmacyLng,
      String pharmacyPhone,
      UUID zoneId,
      String zoneName,
      String customerName,
      String customerPhone,
      String deliveryAddress,
      Double deliveryLat,
      Double deliveryLng,
      int itemsCount,
      String paymentMethod,
      long totalPayablePaise,
      Instant estimatedDeliveryAt,
      Instant slaDeadline,
      String deliveryOtpHash) {}

  record QueuePage(List<QueueOrder> rows, long total) {
    public QueuePage {
      rows = List.copyOf(rows);
    }
  }

  QueuePage listUnassignedReady(UUID zoneId, int page, int limit);

  Optional<OrderDetails> findOrder(UUID orderId);

  void assignRiderOnOrder(UUID orderId, UUID riderId, Instant now);

  void clearRiderOnOrder(UUID orderId, Instant now);

  void advanceStatus(
      UUID orderId,
      String fromStatus,
      String toStatus,
      String actorType,
      UUID actorId,
      String notes,
      Instant now);

  /** Peek plaintext delivery OTP from order Redis cache (may be empty). */
  Optional<String> peekDeliveryOtp(UUID orderId);

  /** Verify delivery OTP against order hash / Redis (EPIC-010). */
  boolean verifyDeliveryOtp(UUID orderId, String otp);

  /** Ensure order has a delivery OTP (hash + Redis); returns plaintext. */
  String ensureDeliveryOtp(UUID orderId, Instant now);
}
