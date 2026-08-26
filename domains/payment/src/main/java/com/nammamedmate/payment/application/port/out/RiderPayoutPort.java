package com.nammamedmate.payment.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-domain rider payout access for EPIC-012 finance façades. Bridged in apps/api onto V042
 * {@code rider_payouts} + riders/zones/trip earnings — no domain→domain compile deps.
 */
public interface RiderPayoutPort {

  record PayoutRecord(
      UUID id,
      UUID riderId,
      String riderName,
      UUID zoneId,
      String zoneName,
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
      String cashfreeTransferId,
      String payoutReference,
      String releaseNotes,
      UUID releasedBy,
      Instant releasedAt,
      int retryCount,
      Instant nextRetryAt,
      String releaseIdempotencyKey) {}

  record RiderSnapshot(UUID id, String name, long codInHandPaise) {}

  record PaymentInstrument(String kind, String reference) {}

  record EarningsEntry(
      LocalDate date,
      UUID orderId,
      String orderNumber,
      long basePayPaise,
      long tipPaise,
      long incentiveBonusPaise,
      long totalPaise,
      boolean onTime,
      BigDecimal distanceKm,
      Instant completedAt) {}

  record ListFilter(
      String storageStatus, LocalDate cycleFrom, UUID zoneId, int limit, int offset) {}

  record ListResult(List<PayoutRecord> payouts, long total) {
    public ListResult {
      payouts = payouts == null ? List.of() : List.copyOf(payouts);
    }
  }

  record SummarySnapshot(
      long totalPending,
      long totalPendingAmountPaise,
      long totalHeld,
      long totalHeldAmountPaise,
      long totalReleasedThisCycle,
      long totalReleasedAmountPaise) {}

  Optional<PayoutRecord> findById(UUID payoutId);

  Optional<PayoutRecord> findByIdempotencyKey(String idempotencyKey);

  Optional<RiderSnapshot> findRider(UUID riderId);

  /** Empty → {@code RIDER_NO_PAYMENT_DETAILS}. */
  Optional<PaymentInstrument> findPaymentInstrument(UUID riderId);

  ListResult list(ListFilter filter);

  SummarySnapshot summary(LocalDate cycleFrom, UUID zoneId);

  ListResult listForRider(UUID riderId, int limit, int offset);

  List<EarningsEntry> listEarnings(
      UUID riderId, LocalDate from, LocalDate to, int limit, int offset);

  long countEarnings(UUID riderId, LocalDate from, LocalDate to);

  Optional<PayoutRecord> findByRiderAndCycle(UUID riderId, LocalDate cycleFrom, LocalDate cycleTo);

  long codFloatLimitPaise();

  boolean claimForRelease(UUID payoutId, UUID riderId, String idempotencyKey, Instant now);

  boolean finalizeRelease(
      UUID payoutId,
      UUID releasedBy,
      Instant releasedAt,
      String cashfreeTransferId,
      String notes,
      String idempotencyKey,
      Instant now);

  void scheduleRetry(
      UUID payoutId, String idempotencyKey, String error, Instant retryAt, Instant now);

  void markFailed(UUID payoutId, String idempotencyKey, String error, Instant now);

  void markBelowThreshold(UUID payoutId, String notes, Instant now);

  void adjustEarningsWallet(UUID riderId, long deltaPaise, Instant now);

  /**
   * PENDING payouts with {@code minPaiseInclusive <= net <= maxPaiseInclusive}, optional cycle
   * filter.
   */
  List<PayoutRecord> listPendingForBulk(
      long minPaiseInclusive, long maxPaiseInclusive, LocalDate cycleFrom, int limit);
}
