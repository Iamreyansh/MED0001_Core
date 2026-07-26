package com.nammamedmate.customer.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly customer maintenance in Asia/Kolkata. Push/SMS delivery of queued notify events remains
 * EPIC-017; this only runs segment recompute + deletion anonymisation.
 */
@Component
@ConditionalOnProperty(
    name = "medmate.customer.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CustomerMaintenanceScheduler {

  private final CustomerMaintenanceService maintenance;

  public CustomerMaintenanceScheduler(CustomerMaintenanceService maintenance) {
    this.maintenance = maintenance;
  }

  @Scheduled(cron = "0 15 2 * * *", zone = "Asia/Kolkata")
  public void runNightlyJobs() {
    maintenance.recomputeSegments();
    maintenance.anonymiseDueAccounts();
  }
}
