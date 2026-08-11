package com.nammamedmate.analytics.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read/write ports for platform overview KPIs, charts, leaderboards, and daily snapshots. */
public interface PlatformOverviewStore {

  record KpiTotals(
      long gmvPaise,
      long ordersCount,
      long deliveredCount,
      long cancelledCount,
      long refundsPaise,
      long cancellationsPaise,
      long commissionPaise,
      long cogsEstimatePaise,
      long activeCustomers,
      long repeatCustomers,
      long newCustomers) {}

  record GmvTrendPoint(LocalDate date, long gmvPaise) {}

  record CategoryMixRow(String category, long gmvPaise) {}

  record PaymentMixRow(String method, long ordersCount) {}

  record ZoneSalesRow(UUID zoneId, String zoneName, long gmvPaise, long ordersCount) {}

  record PharmacyLeader(
      UUID pharmacyId,
      String name,
      String area,
      double rating,
      long orders,
      long gmvPaise,
      double fillRatePct) {}

  record RiderLeader(
      UUID riderId,
      String name,
      String zone,
      long trips,
      double onTimePct,
      double rating,
      long earningsPaise) {}

  KpiTotals liveKpis(Instant fromInclusive, Instant toExclusive);

  KpiTotals aggregatedKpis(LocalDate fromInclusive, LocalDate toInclusive);

  List<GmvTrendPoint> liveGmvTrend(Instant fromInclusive, Instant toExclusive);

  List<GmvTrendPoint> aggregatedGmvTrend(LocalDate fromInclusive, LocalDate toInclusive);

  List<CategoryMixRow> liveCategoryMix(Instant fromInclusive, Instant toExclusive);

  List<CategoryMixRow> aggregatedCategoryMix(LocalDate fromInclusive, LocalDate toInclusive);

  List<PaymentMixRow> livePaymentMix(Instant fromInclusive, Instant toExclusive);

  List<PaymentMixRow> aggregatedPaymentMix(LocalDate fromInclusive, LocalDate toInclusive);

  List<ZoneSalesRow> liveSalesByZone(Instant fromInclusive, Instant toExclusive);

  List<ZoneSalesRow> aggregatedSalesByZone(LocalDate fromInclusive, LocalDate toInclusive);

  List<PharmacyLeader> topPharmacies(Instant fromInclusive, Instant toExclusive, int topN);

  List<RiderLeader> topRiders(Instant fromInclusive, Instant toExclusive, int topN);

  /** Recompute platform-wide daily snapshots for [from, to] inclusive (IST calendar dates). */
  void refreshDailySnapshots(LocalDate fromInclusive, LocalDate toInclusive);
}
