package com.nammamedmate.payment.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-domain settlement access for EPIC-012 finance façades. Bridged in apps/api onto V019 {@code
 * settlement} + pharmacy/order tables — no domain→domain compile deps.
 */
public interface PharmacySettlementPort {

  record SettlementRecord(
      UUID id,
      UUID pharmacyId,
      String pharmacyName,
      LocalDate cycleFrom,
      LocalDate cycleTo,
      long gmvPaise,
      BigDecimal commissionPct,
      long commissionEarnedPaise,
      long tcsDeductedPaise,
      long gstOnCommissionPaise,
      long netPayablePaise,
      int ordersCount,
      String status,
      String holdReason,
      UUID heldBy,
      Instant heldAt,
      UUID releasedBy,
      Instant releasedAt,
      String razorpayxPayoutId,
      String notes,
      String releaseIdempotencyKey) {}

  record BankSnapshot(
      String accountNumberMasked, String bankName, String ifsc, String verificationStatus) {}

  record LineItem(
      UUID orderId,
      String orderNumber,
      Instant deliveredAt,
      long gmvPaise,
      BigDecimal commissionPct,
      long commissionPaise,
      long tcsPaise,
      long netPaise) {}

  record ListFilter(
      String storageStatus, UUID pharmacyId, LocalDate cycleFrom, int limit, int offset) {}

  record ListResult(List<SettlementRecord> settlements, long total) {
    public ListResult {
      settlements = settlements == null ? List.of() : List.copyOf(settlements);
    }
  }

  record KpiSnapshot(
      long gmvTodayPaise,
      long commissionTodayPaise,
      long payoutDueTotalPaise,
      long payoutReleasedTodayPaise) {}

  record Totals(
      long totalGmvPaise,
      long totalCommissionPaise,
      long totalTcsPaise,
      long totalNetPayablePaise) {}

  Optional<SettlementRecord> findById(UUID settlementId);

  Optional<SettlementRecord> findByIdempotencyKey(String idempotencyKey);

  ListResult list(ListFilter filter);

  Totals totals(ListFilter filter);

  KpiSnapshot kpis(Instant dayStartIst, Instant dayEndIst);

  Optional<BankSnapshot> findVerifiedBank(UUID pharmacyId);

  List<LineItem> lineItems(
      UUID pharmacyId, LocalDate cycleFrom, LocalDate cycleTo, BigDecimal commissionPct);

  boolean claimForRelease(UUID settlementId, UUID pharmacyId, String idempotencyKey, Instant now);

  boolean finalizeRelease(
      UUID settlementId,
      UUID releasedBy,
      Instant releasedAt,
      String razorpayxPayoutId,
      String notes,
      String idempotencyKey,
      Instant now);

  void markReleaseFailed(UUID settlementId, String idempotencyKey, Instant now);

  void markHeld(UUID settlementId, UUID heldBy, String reason, String notes, Instant heldAt);

  void markBelowThreshold(UUID settlementId, String notes, Instant now);

  List<SettlementRecord> listPendingForBulk(long maxNetPaiseInclusive, int limit);
}
