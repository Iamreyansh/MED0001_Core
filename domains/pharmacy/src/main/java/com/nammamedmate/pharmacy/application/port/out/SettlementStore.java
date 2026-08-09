package com.nammamedmate.pharmacy.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementStore {

  record SettlementRow(
      UUID id,
      UUID pharmacyId,
      LocalDate periodStart,
      LocalDate periodEnd,
      long gmvPaise,
      BigDecimal commissionPct,
      long commissionEarnedPaise,
      BigDecimal tcsRatePct,
      long tcsDeductedPaise,
      long netPaidPaise,
      String status,
      String holdReason,
      UUID releasedBy,
      Instant releasedAt,
      Instant paidAt,
      String razorpayxPayoutId,
      String utrNumber,
      String receiptUrl,
      String releaseIdempotencyKey,
      Instant createdAt,
      Instant updatedAt) {}

  record ListFilter(String status, LocalDate fromDate, LocalDate toDate, int limit, int offset) {}

  record ListResult(List<SettlementRow> settlements, long total) {
    public ListResult {
      settlements = settlements == null ? List.of() : List.copyOf(settlements);
    }
  }

  Optional<SettlementRow> findById(UUID settlementId);

  Optional<SettlementRow> findByIdForPharmacy(UUID pharmacyId, UUID settlementId);

  Optional<SettlementRow> findByIdempotencyKey(String idempotencyKey);

  Optional<SettlementRow> findByRazorpayxPayoutId(String razorpayxPayoutId);

  Optional<SettlementRow> findForPeriod(
      UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd);

  Optional<SettlementRow> findLatestPaid(UUID pharmacyId);

  void insert(SettlementRow row);

  void updateReleased(
      UUID settlementId,
      String status,
      UUID releasedBy,
      Instant releasedAt,
      String razorpayxPayoutId,
      String idempotencyKey,
      Instant updatedAt);

  /** Claims a PENDING_RELEASE row for payout initiation (sets idempotency key). */
  boolean claimForRelease(
      UUID settlementId, UUID pharmacyId, String idempotencyKey, Instant updatedAt);

  /** Moves a claimed row to RELEASED after successful payout initiation. */
  boolean finalizeRelease(
      UUID settlementId,
      UUID releasedBy,
      Instant releasedAt,
      String razorpayxPayoutId,
      String idempotencyKey,
      Instant updatedAt);

  /** Marks a claimed row FAILED when payout initiation fails. */
  boolean markReleaseFailed(UUID settlementId, String idempotencyKey, Instant updatedAt);

  void updateHeld(UUID settlementId, String reason, Instant updatedAt);

  void updatePaid(
      UUID settlementId, String utrNumber, String receiptUrl, Instant paidAt, Instant updatedAt);

  ListResult list(UUID pharmacyId, ListFilter filter);

  boolean existsForPeriod(UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd);

  /** Unconsumed BELOW_THRESHOLD_CARRIED nets to fold into the next cycle (EPIC-012 STORY-003). */
  long sumUnconsumedCarryForwardPaise(UUID pharmacyId);

  void markCarryForwardConsumed(UUID pharmacyId, Instant consumedAt);
}
