package com.nammamedmate.payment.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-domain COD float / reconciliation access for EPIC-012 finance façades. Bridged in apps/api
 * onto V041 {@code cod_collections}/{@code cod_deposits} + V062 {@code cod_reconciliation_report}.
 */
public interface CodFloatPort {

  long floatLimitPaise();

  record FloatRiderRow(
      UUID riderId,
      String riderName,
      String zoneName,
      long collectedPaise,
      long depositedPaise,
      long inHandPaise,
      Instant lastDepositAt) {}

  record FloatSnapshot(
      List<FloatRiderRow> riders,
      long total,
      long totalInTransitPaise,
      long collectedTodayPaise,
      long depositedTodayPaise,
      long floatRiskAmountPaise,
      int floatRiskRidersCount) {
    public FloatSnapshot {
      riders = riders == null ? List.of() : List.copyOf(riders);
    }
  }

  record RiderDayBreakdown(
      UUID riderId, String riderName, int orders, long collectedPaise, long depositedPaise) {}

  record DayAggregates(
      int totalCodOrders,
      long totalCodAmountPaise,
      long collectedPaise,
      long depositedPaise,
      List<RiderDayBreakdown> riders) {
    public DayAggregates {
      riders = riders == null ? List.of() : List.copyOf(riders);
    }
  }

  record ReportRecord(
      UUID id,
      LocalDate reportDate,
      int totalCodOrders,
      long totalCodAmountPaise,
      long collectedByRidersPaise,
      long depositedToPlatformPaise,
      long outstandingFloatPaise,
      long variancePaise,
      String varianceReason,
      String reconciliationStatus,
      boolean alertSent,
      Instant generatedAt,
      UUID triggeredBy,
      String riderBreakdownJson) {}

  FloatSnapshot floatBoard(
      UUID zoneId,
      boolean riskOnly,
      Instant dayStart,
      Instant dayEnd,
      long limitPaise,
      int page,
      int limit);

  DayAggregates aggregatesForDay(Instant dayStart, Instant dayEnd);

  Optional<ReportRecord> findReport(LocalDate reportDate);

  /** Insert PENDING job row; returns false when a PENDING job for the date already exists. */
  boolean tryClaimJob(UUID jobId, LocalDate reportDate, UUID triggeredBy, Instant now);

  void completeReport(ReportRecord report);

  /** True when a COD_DEPOSIT ledger row already exists for this deposit id. */
  boolean hasCodDepositLedgerEntry(UUID depositId);
}
