package com.nammamedmate.pharmacy.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly pharmacy performance snapshots at 02:00 IST (pharmacy_performance_aggregator). */
@Component
@ConditionalOnProperty(
    name = "medmate.pharmacy.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PharmacyPerformanceAggregatorScheduler {

  private final PharmacyPerformanceAggregatorService aggregator;

  public PharmacyPerformanceAggregatorScheduler(PharmacyPerformanceAggregatorService aggregator) {
    this.aggregator = aggregator;
  }

  @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
  public void runNightlyAggregation() {
    aggregator.aggregateAll();
  }
}
