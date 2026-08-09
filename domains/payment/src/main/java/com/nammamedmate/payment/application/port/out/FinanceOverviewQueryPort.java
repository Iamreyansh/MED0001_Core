package com.nammamedmate.payment.application.port.out;

import java.time.Instant;
import java.util.List;

/** Read-only finance dashboard aggregates (EPIC-012 STORY-009). */
public interface FinanceOverviewQueryPort {

  record KpiSnapshot(
      long gmvTodayPaise,
      long platformRevenueTodayPaise,
      long pharmacyPayoutDuePaise,
      long riderPayoutDuePaise,
      long refundsPendingCount,
      long refundsPendingValuePaise,
      long codInHandPaise,
      long activeWalletBalancePaise,
      long gatewayFeesTodayPaise) {}

  record PeriodTotals(
      long gmvPaise,
      long commissionPaise,
      long refundsPaise,
      long gatewayFeesPaise,
      long pharmacyPayoutPaise,
      long riderPayoutPaise,
      long tcsPaise,
      long ordersCount,
      long codOrdersCount,
      long capturedOrdersCount) {}

  record ChartPoint(String label, long gmvPaise, long ordersCount) {}

  enum ChartGranularity {
    HOURLY,
    DAILY
  }

  KpiSnapshot kpi(Instant dayStartInclusive, Instant dayEndExclusive);

  PeriodTotals periodTotals(Instant fromInclusive, Instant toExclusive);

  List<ChartPoint> gmvChart(
      Instant fromInclusive, Instant toExclusive, ChartGranularity granularity);

  /** Sum of ORDER_GMV credits in [from, to). */
  long gmvSum(Instant fromInclusive, Instant toExclusive);
}
