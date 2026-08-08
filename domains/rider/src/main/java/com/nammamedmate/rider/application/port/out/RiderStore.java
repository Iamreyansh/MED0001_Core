package com.nammamedmate.rider.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiderStore {

  record RiderRecord(
      UUID id,
      String name,
      String phone,
      String email,
      String vehicleType,
      String vehiclePlateNumber,
      UUID primaryZoneId,
      String status,
      String kycStatus,
      Instant kycSubmittedAt,
      Instant kycReviewedAt,
      UUID kycReviewedBy,
      String kycRejectionReason,
      String kycRejectionNotes,
      boolean aadhaarVerified,
      BigDecimal avgRating,
      int totalTrips,
      BigDecimal onTimePct,
      long earningsWalletBalancePaise,
      long codInHandPaise,
      int dailyStreakDays,
      String blockedReason,
      UUID blockedBy,
      Instant blockedAt,
      Instant createdAt,
      Instant updatedAt) {}

  record ListFilter(String status, String sort, String order, int page, int limit) {}

  record PageResult(List<RiderRecord> rows, long total) {
    public PageResult {
      rows = List.copyOf(rows);
    }
  }

  void insert(RiderRecord rider);

  Optional<RiderRecord> findById(UUID id);

  Optional<RiderRecord> findByPhone(String phone);

  boolean existsByPhone(String phone);

  void update(RiderRecord rider);

  PageResult list(ListFilter filter);

  /** Live availability: status ONLINE/OFFLINE/ON_TRIP + optional current zone. */
  void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt);

  void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt);

  /** STORY-004: bump last_location_at on GPS ingest. */
  default void updateLastLocationAt(UUID id, Instant lastLocationAt) {}

  /** STORY-007: atomic COD float adjust; returns new balance. Rejects negative result. */
  default long adjustCodInHand(UUID id, long deltaPaise, Instant updatedAt) {
    throw new UnsupportedOperationException("adjustCodInHand");
  }

  /** STORY-008: credit trip earnings into wallet. */
  default long adjustEarningsWallet(UUID id, long deltaPaise, Instant updatedAt) {
    throw new UnsupportedOperationException("adjustEarningsWallet");
  }

  /** STORY-008: streak + last delivery date + optional streak bonus pending. */
  default void updateStreak(
      UUID id,
      int dailyStreakDays,
      LocalDate lastDeliveryDate,
      boolean streakBonusPending,
      Instant updatedAt) {
    throw new UnsupportedOperationException("updateStreak");
  }

  default long payoutCarryForwardPaise(UUID id) {
    return 0L;
  }

  default void setPayoutCarryForward(UUID id, long paise, Instant updatedAt) {
    throw new UnsupportedOperationException("setPayoutCarryForward");
  }

  default Optional<LocalDate> lastDeliveryDate(UUID id) {
    return Optional.empty();
  }

  default boolean streakBonusPending(UUID id) {
    return false;
  }

  default void clearStreakBonusPending(UUID id, Instant updatedAt) {
    throw new UnsupportedOperationException("clearStreakBonusPending");
  }

  /** Active riders with earnings activity or carry-forward for payout compute. */
  default List<UUID> listIdsForPayoutCompute() {
    return List.of();
  }
}
