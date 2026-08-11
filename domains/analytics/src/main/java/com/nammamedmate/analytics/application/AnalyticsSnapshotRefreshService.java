package com.nammamedmate.analytics.application;

import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore;
import com.nammamedmate.analytics.domain.PeriodResolver;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Nightly refresh of analytics_daily_snapshots (+ mix tables). */
@Service
public class AnalyticsSnapshotRefreshService {

  private final PlatformOverviewStore store;
  private final Clock clock;

  public AnalyticsSnapshotRefreshService(PlatformOverviewStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /** Refresh yesterday (IST) — primary nightly job target. */
  @Transactional
  public void refreshYesterday() {
    LocalDate yesterday = LocalDate.now(clock.withZone(PeriodResolver.IST)).minusDays(1);
    store.refreshDailySnapshots(yesterday, yesterday);
  }

  /** Backfill inclusive IST date range (tests / ops). */
  @Transactional
  public void refreshRange(LocalDate fromInclusive, LocalDate toInclusive) {
    store.refreshDailySnapshots(fromInclusive, toInclusive);
  }
}
