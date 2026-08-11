package com.nammamedmate.analytics.application;

import com.nammamedmate.analytics.application.port.out.PlatformOpsStore;
import com.nammamedmate.analytics.domain.PeriodResolver;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 15-minute ops snapshot refresh during business hours (06:00–23:00 IST). */
@Service
public class OpsSnapshotRefreshService {

  private static final LocalTime BUSINESS_START = LocalTime.of(6, 0);
  private static final LocalTime BUSINESS_END = LocalTime.of(23, 0);

  private final PlatformOpsStore store;
  private final Clock clock;

  public OpsSnapshotRefreshService(PlatformOpsStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /** Refresh today's IST ops window when inside business hours. */
  @Transactional
  public void refreshIfBusinessHours() {
    var ist = clock.withZone(PeriodResolver.IST);
    LocalTime now = LocalTime.now(ist);
    if (now.isBefore(BUSINESS_START) || now.isAfter(BUSINESS_END)) {
      return;
    }
    LocalDate today = LocalDate.now(ist);
    store.refreshOpsSnapshots(today, today);
  }

  @Transactional
  public void refreshRange(LocalDate fromInclusive, LocalDate toInclusive) {
    store.refreshOpsSnapshots(fromInclusive, toInclusive);
  }
}
