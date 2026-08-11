package com.nammamedmate.analytics.application;

import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore;
import com.nammamedmate.analytics.domain.PeriodResolver;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Nightly zone daily snapshots + 28-day demand heatmap recompute (STORY-005). */
@Service
public class GeographyAnalyticsRefreshService {

  private final PlatformGeographyStore store;
  private final Clock clock;

  public GeographyAnalyticsRefreshService(PlatformGeographyStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Transactional
  public void refreshYesterdayAndHeatmap() {
    LocalDate yesterday = LocalDate.now(clock.withZone(PeriodResolver.IST)).minusDays(1);
    LocalDate today = LocalDate.now(clock.withZone(PeriodResolver.IST));
    store.refreshZoneDaily(yesterday, yesterday);
    // Rolling 28-day window ending at today start (exclusive end = today).
    store.refreshHourlyDemand(today, GeographyAnalyticsService.HEATMAP_WINDOW_DAYS);
  }

  @Transactional
  public void refreshRange(LocalDate fromInclusive, LocalDate toInclusive) {
    store.refreshZoneDaily(fromInclusive, toInclusive);
  }
}
