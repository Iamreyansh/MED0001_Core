package com.nammamedmate.pharmacy.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyPerformanceSnapshotStore {

  record SnapshotRow(
      UUID id,
      UUID pharmacyId,
      String period,
      LocalDate periodStart,
      LocalDate periodEnd,
      int ordersReceived,
      int ordersFulfilled,
      int ordersCancelled,
      BigDecimal fillRatePct,
      BigDecimal onTimePrepPct,
      BigDecimal cancelRatePct,
      BigDecimal outOfStockRatePct,
      BigDecimal avgPrepMinutes,
      int complaintCount,
      BigDecimal avgRating,
      int reviewCount,
      long gmvPeriodPaise,
      short consecutiveLowFillDays,
      String fillRateTrend,
      String cancelRateTrend,
      Instant computedAt) {}

  Optional<SnapshotRow> find(UUID pharmacyId, String period);

  void upsert(SnapshotRow row, Instant updatedAt);
}
