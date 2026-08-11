package com.nammamedmate.analytics.application;

import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore;
import com.nammamedmate.analytics.domain.PeriodResolver;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Nightly pharmacy analytics snapshot + dead-stock flag refresh (02:00 IST). */
@Service
public class PharmacyAnalyticsRefreshService {

  private final PharmacyAnalyticsStore store;
  private final Clock clock;

  public PharmacyAnalyticsRefreshService(PharmacyAnalyticsStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Transactional
  public void refreshYesterdayAndDeadStock() {
    LocalDate yesterday = LocalDate.now(clock.withZone(PeriodResolver.IST)).minusDays(1);
    LocalDate today = LocalDate.now(clock.withZone(PeriodResolver.IST));
    store.refreshDailySnapshots(yesterday, yesterday);
    store.refreshDeadStockFlags(today);
  }

  @Transactional
  public void refreshRange(LocalDate fromInclusive, LocalDate toInclusive) {
    store.refreshDailySnapshots(fromInclusive, toInclusive);
  }
}
