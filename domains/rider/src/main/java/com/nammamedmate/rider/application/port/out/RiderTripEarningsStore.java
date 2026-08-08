package com.nammamedmate.rider.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Rider per-trip earnings ledger (STORY-003 stub → STORY-008). */
public interface RiderTripEarningsStore {

  record EarningsRecord(
      UUID id,
      UUID riderId,
      UUID orderId,
      UUID assignmentId,
      LocalDate deliveryDate,
      long basePayPaise,
      long tipPaise,
      long incentiveBonusPaise,
      long totalPaise,
      boolean onTime,
      Integer customerRating,
      BigDecimal distanceKm,
      Integer durationMinutes,
      Instant createdAt) {}

  record TripView(
      UUID orderId,
      String orderNumber,
      String pickupPharmacy,
      String deliveryArea,
      BigDecimal distanceKm,
      int durationMinutes,
      long basePayPaise,
      long tipPaise,
      long incentiveBonusPaise,
      long totalPaise,
      boolean onTime,
      Integer customerRating,
      Instant completedAt) {}

  record PeriodTotals(
      long basePaise, long incentivesPaise, long tipsPaise, long totalPaise, int trips) {}

  record LifetimeTotals(long totalEarningsPaise, int totalTrips) {}

  void insert(EarningsRecord row);

  default PeriodTotals sumForRider(UUID riderId, LocalDate fromInclusive, LocalDate toInclusive) {
    return new PeriodTotals(0, 0, 0, 0, 0);
  }

  default LifetimeTotals lifetime(UUID riderId) {
    return new LifetimeTotals(0, 0);
  }

  default List<TripView> listTrips(
      UUID riderId, LocalDate from, LocalDate to, int offset, int limit) {
    return List.of();
  }

  default long countTrips(UUID riderId, LocalDate from, LocalDate to) {
    return 0L;
  }

  default Optional<BigDecimal> avgRating(UUID riderId) {
    return Optional.empty();
  }

  default BigDecimal totalDistanceKm(UUID riderId) {
    return BigDecimal.ZERO;
  }

  default int countOnTime(UUID riderId) {
    return 0;
  }

  default int countRated(UUID riderId) {
    return 0;
  }

  default List<UUID> distinctRidersWithEarnings(LocalDate from, LocalDate to) {
    return List.of();
  }
}
