package com.nammamedmate.analytics.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Read/write ports for growth & cohort analytics (EPIC-016 STORY-003). */
public interface PlatformGrowthStore {

  record GrowthTotals(long activeCustomers, long newCustomers, long repeatCustomers) {}

  record CohortCell(
      String cohortWeek,
      int cohortSize,
      int elapsedWeek,
      int retainedCount,
      BigDecimal retentionPct,
      Instant computedAt) {}

  record Month1Retention(String cohortWeek, BigDecimal retentionPct) {}

  record AcquisitionRow(String source, long newUsers, long orders, long gmvPaise) {}

  record SpendRow(String source, BigDecimal spendRs) {}

  record OrderTrendPoint(
      LocalDate date, long totalOrders, long newCustomerOrders, long returningCustomerOrders) {}

  GrowthTotals liveGrowth(Instant fromInclusive, Instant toExclusive);

  GrowthTotals aggregatedGrowth(LocalDate fromInclusive, LocalDate toInclusive);

  List<CohortCell> cohortMatrix(int cohortCount);

  Optional<Instant> cohortLastComputedAt();

  Optional<Month1Retention> month1Retention(LocalDate asOfIst);

  List<AcquisitionRow> liveAcquisition(Instant fromInclusive, Instant toExclusive);

  List<AcquisitionRow> aggregatedAcquisition(LocalDate fromInclusive, LocalDate toInclusive);

  List<SpendRow> campaignSpend(LocalDate fromInclusive, LocalDate toInclusive);

  List<OrderTrendPoint> orderTrendDaily(Instant fromInclusive, Instant toExclusive);

  List<OrderTrendPoint> orderTrendWeekly(Instant fromInclusive, Instant toExclusive);

  /** Recompute retention matrix for the last {@code cohortWeeks} ISO weeks (IST). */
  void refreshCohortRetention(int cohortWeeks, Instant computedAt);

  /** Upsert acquisition daily facts for IST calendar dates [from, to]. */
  void refreshAcquisitionDaily(LocalDate fromInclusive, LocalDate toInclusive);
}
