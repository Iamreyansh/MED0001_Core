package com.nammamedmate.analytics.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.nammamedmate.analytics.application.port.out.PlatformOpsStore;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AnalyticsSnapshotSchedulerTest {

  @Test
  void runDelegatesToYesterdayRefresh() {
    AnalyticsSnapshotRefreshService refresh = mock(AnalyticsSnapshotRefreshService.class);
    OpsSnapshotRefreshService ops = mock(OpsSnapshotRefreshService.class);
    CohortRefreshService cohort = mock(CohortRefreshService.class);
    PharmacyAnalyticsRefreshService pharmacy = mock(PharmacyAnalyticsRefreshService.class);
    GeographyAnalyticsRefreshService geography = mock(GeographyAnalyticsRefreshService.class);
    new AnalyticsSnapshotScheduler(refresh, ops, cohort, pharmacy, geography).run();
    verify(refresh).refreshYesterday();
    verify(pharmacy).refreshYesterdayAndDeadStock();
    verify(geography).refreshYesterdayAndHeatmap();
  }

  @Test
  void runOpsDelegatesToBusinessHoursRefresh() {
    AnalyticsSnapshotRefreshService refresh = mock(AnalyticsSnapshotRefreshService.class);
    OpsSnapshotRefreshService ops = mock(OpsSnapshotRefreshService.class);
    CohortRefreshService cohort = mock(CohortRefreshService.class);
    PharmacyAnalyticsRefreshService pharmacy = mock(PharmacyAnalyticsRefreshService.class);
    GeographyAnalyticsRefreshService geography = mock(GeographyAnalyticsRefreshService.class);
    new AnalyticsSnapshotScheduler(refresh, ops, cohort, pharmacy, geography).runOps();
    verify(ops).refreshIfBusinessHours();
  }

  @Test
  void runCohortDelegatesToWeeklyRefresh() {
    AnalyticsSnapshotRefreshService refresh = mock(AnalyticsSnapshotRefreshService.class);
    OpsSnapshotRefreshService ops = mock(OpsSnapshotRefreshService.class);
    CohortRefreshService cohort = mock(CohortRefreshService.class);
    PharmacyAnalyticsRefreshService pharmacy = mock(PharmacyAnalyticsRefreshService.class);
    GeographyAnalyticsRefreshService geography = mock(GeographyAnalyticsRefreshService.class);
    new AnalyticsSnapshotScheduler(refresh, ops, cohort, pharmacy, geography).runCohort();
    verify(cohort).refreshWeekly();
  }

  @Test
  void refreshServiceUsesIstYesterdayAndRange() {
    PlatformOverviewStore store = mock(PlatformOverviewStore.class);
    Instant now = Instant.parse("2026-07-24T16:00:00Z");
    AnalyticsSnapshotRefreshService svc =
        new AnalyticsSnapshotRefreshService(store, Clock.fixed(now, ZoneOffset.UTC));
    svc.refreshYesterday();
    verify(store).refreshDailySnapshots(LocalDate.of(2026, 7, 23), LocalDate.of(2026, 7, 23));
    svc.refreshRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));
    verify(store).refreshDailySnapshots(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));
  }

  @Test
  void opsRefreshRunsDuringBusinessHours() {
    PlatformOpsStore store = mock(PlatformOpsStore.class);
    // 2026-07-24 16:00 UTC = 21:30 IST — inside 06:00–23:00
    OpsSnapshotRefreshService svc =
        new OpsSnapshotRefreshService(
            store, Clock.fixed(Instant.parse("2026-07-24T16:00:00Z"), ZoneOffset.UTC));
    svc.refreshIfBusinessHours();
    verify(store).refreshOpsSnapshots(LocalDate.of(2026, 7, 24), LocalDate.of(2026, 7, 24));
    svc.refreshRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));
    verify(store).refreshOpsSnapshots(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));
  }

  @Test
  void opsRefreshSkipsOutsideBusinessHours() {
    PlatformOpsStore store = mock(PlatformOpsStore.class);
    // Use 20:00 UTC previous day = 01:30 IST next — outside.
    OpsSnapshotRefreshService svc =
        new OpsSnapshotRefreshService(
            store, Clock.fixed(Instant.parse("2026-07-23T20:00:00Z"), ZoneOffset.UTC));
    svc.refreshIfBusinessHours();
    verify(store, never()).refreshOpsSnapshots(any(), any());
  }
}
