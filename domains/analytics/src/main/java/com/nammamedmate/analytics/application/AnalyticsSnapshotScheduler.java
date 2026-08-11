package com.nammamedmate.analytics.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "medmate.analytics.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AnalyticsSnapshotScheduler {

  private final AnalyticsSnapshotRefreshService refresh;
  private final OpsSnapshotRefreshService opsRefresh;
  private final CohortRefreshService cohortRefresh;
  private final PharmacyAnalyticsRefreshService pharmacyRefresh;
  private final GeographyAnalyticsRefreshService geographyRefresh;

  public AnalyticsSnapshotScheduler(
      AnalyticsSnapshotRefreshService refresh,
      OpsSnapshotRefreshService opsRefresh,
      CohortRefreshService cohortRefresh,
      PharmacyAnalyticsRefreshService pharmacyRefresh,
      GeographyAnalyticsRefreshService geographyRefresh) {
    this.refresh = refresh;
    this.opsRefresh = opsRefresh;
    this.cohortRefresh = cohortRefresh;
    this.pharmacyRefresh = pharmacyRefresh;
    this.geographyRefresh = geographyRefresh;
  }

  /** Daily overview + pharmacy + geography/heatmap at 02:00 Asia/Kolkata. */
  @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
  public void run() {
    refresh.refreshYesterday();
    pharmacyRefresh.refreshYesterdayAndDeadStock();
    geographyRefresh.refreshYesterdayAndHeatmap();
  }

  /** Ops pre-agg every 15 minutes during 06:00–23:00 IST (guard inside refresh). */
  @Scheduled(cron = "0 */15 6-23 * * *", zone = "Asia/Kolkata")
  public void runOps() {
    opsRefresh.refreshIfBusinessHours();
  }

  /** Cohort retention recompute every Sunday 03:00 Asia/Kolkata. */
  @Scheduled(cron = "0 0 3 * * SUN", zone = "Asia/Kolkata")
  public void runCohort() {
    cohortRefresh.refreshWeekly();
  }
}
