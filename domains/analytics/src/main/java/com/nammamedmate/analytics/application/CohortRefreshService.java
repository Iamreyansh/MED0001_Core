package com.nammamedmate.analytics.application;

import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore;
import com.nammamedmate.analytics.domain.PeriodResolver;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Weekly cohort retention recompute (Sunday 03:00 IST) + acquisition daily refresh helper. */
@Service
public class CohortRefreshService {

  static final int DEFAULT_COHORT_WEEKS = 26;

  private final PlatformGrowthStore store;
  private final Clock clock;

  public CohortRefreshService(PlatformGrowthStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Transactional
  public void refreshWeekly() {
    store.refreshCohortRetention(DEFAULT_COHORT_WEEKS, clock.instant());
    LocalDate today = LocalDate.now(clock.withZone(PeriodResolver.IST));
    // Backfill last 90 days of acquisition facts while the weekly job runs.
    store.refreshAcquisitionDaily(today.minusDays(90), today);
  }
}
