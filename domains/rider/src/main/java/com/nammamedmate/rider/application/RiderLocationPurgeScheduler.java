package com.nammamedmate.rider.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** AC-007: purge rider_locations older than 30 days (Asia/Kolkata nightly). */
@Component
@ConditionalOnProperty(
    name = "medmate.rider.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RiderLocationPurgeScheduler {

  private final RiderLocationService locations;

  public RiderLocationPurgeScheduler(RiderLocationService locations) {
    this.locations = locations;
  }

  @Scheduled(cron = "0 20 2 * * *", zone = "Asia/Kolkata")
  @Transactional
  public void purge() {
    locations.purgeOlderThan30Days();
  }
}
