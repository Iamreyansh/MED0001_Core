package com.nammamedmate.rider.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiderPayoutStore {

  record PayoutRecord(
      UUID id,
      UUID riderId,
      LocalDate cycleFrom,
      LocalDate cycleTo,
      long baseEarningsPaise,
      long incentivesPaise,
      long tipsPaise,
      long streakBonusPaise,
      long carryForwardPaise,
      long codDeductedPaise,
      long netPayoutPaise,
      String status,
      String holdReason,
      String razorpayPayoutId,
      String payoutReference,
      String releaseNotes,
      UUID releasedBy,
      Instant releasedAt,
      int retryCount,
      Instant nextRetryAt,
      Instant lastAttemptAt,
      Instant createdAt,
      Instant updatedAt) {}

  void insert(PayoutRecord row);

  void update(PayoutRecord row);

  Optional<PayoutRecord> findById(UUID id);

  Optional<PayoutRecord> findByIdempotencyKey(String idempotencyKey);

  /**
   * Claims a releasable payout for disbursement (sets Idempotency-Key once). Returns false if
   * already claimed or not in a releasable status.
   */
  boolean claimForRelease(UUID payoutId, UUID riderId, String idempotencyKey, Instant updatedAt);

  Optional<PayoutRecord> findByRiderAndCycle(UUID riderId, LocalDate cycleFrom, LocalDate cycleTo);

  List<PayoutRecord> listForRider(
      UUID riderId, LocalDate from, LocalDate to, int offset, int limit);

  long countForRider(UUID riderId, LocalDate from, LocalDate to);

  /** PENDING payouts with next_retry_at <= now and retry_count < 1. */
  List<PayoutRecord> findDueForRetry(Instant now, int limit);
}
