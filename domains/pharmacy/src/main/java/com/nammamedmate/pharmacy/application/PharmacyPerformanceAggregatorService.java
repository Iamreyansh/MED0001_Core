package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.PeriodMetrics;
import com.nammamedmate.pharmacy.application.port.out.PharmacyPerformanceSnapshotStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyPerformanceSnapshotStore.SnapshotRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyPerformanceAggregatorService {

  private static final int[] PERIOD_DAYS = {7, 30, 90};

  private final AdminPharmacyStore pharmacies;
  private final PharmacyOrderMetricsPort orderMetrics;
  private final PharmacyPerformanceSnapshotStore snapshots;
  private final AdminPharmacyPerformanceService performanceService;
  private final Clock clock;

  public PharmacyPerformanceAggregatorService(
      AdminPharmacyStore pharmacies,
      PharmacyOrderMetricsPort orderMetrics,
      PharmacyPerformanceSnapshotStore snapshots,
      AdminPharmacyPerformanceService performanceService,
      Clock clock) {
    this.pharmacies = pharmacies;
    this.orderMetrics = orderMetrics;
    this.snapshots = snapshots;
    this.performanceService = performanceService;
    this.clock = clock;
  }

  @Transactional
  public void aggregateAll() {
    Instant now = clock.instant();
    LocalDate periodEnd = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1);
    List<UUID> pharmacyIds = pharmacies.listActivePharmacyIds();
    for (UUID pharmacyId : pharmacyIds) {
      aggregatePharmacy(pharmacyId, periodEnd, now);
    }
  }

  void aggregatePharmacy(UUID pharmacyId, LocalDate periodEnd, Instant computedAt) {
    PeriodMetrics current7 = orderMetrics.periodMetrics(pharmacyId, periodEnd, 7);
    PeriodMetrics previous7 = orderMetrics.periodMetrics(pharmacyId, periodEnd.minusDays(7), 7);
    String fillTrend =
        AdminPharmacyPerformanceService.computeTrend(
            current7.fillRatePct(), previous7.fillRatePct());
    String cancelTrend =
        AdminPharmacyPerformanceService.computeTrend(
            current7.cancelRatePct(), previous7.cancelRatePct());

    for (int days : PERIOD_DAYS) {
      PeriodMetrics metrics = orderMetrics.periodMetrics(pharmacyId, periodEnd, days);
      String dbPeriod =
          switch (days) {
            case 7 -> "7D";
            case 90 -> "90D";
            default -> "30D";
          };
      LocalDate periodStart = periodEnd.minusDays(days - 1L);
      SnapshotRow row =
          new SnapshotRow(
              Ids.newId(),
              pharmacyId,
              dbPeriod,
              periodStart,
              periodEnd,
              metrics.ordersReceived(),
              metrics.ordersFulfilled(),
              metrics.ordersCancelled(),
              metrics.fillRatePct(),
              metrics.onTimePrepPct(),
              metrics.cancelRatePct(),
              metrics.outOfStockRatePct(),
              metrics.avgPrepMinutes(),
              metrics.complaintCount(),
              metrics.avgRating(),
              metrics.reviewCount(),
              metrics.gmvPeriodPaise(),
              metrics.consecutiveLowFillDays(),
              fillTrend,
              cancelTrend,
              computedAt);
      snapshots.upsert(row, computedAt);
      performanceService.writeCache(pharmacyId, dbPeriod, row);
    }
  }
}
