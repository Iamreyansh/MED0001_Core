package com.nammamedmate.observability_ops.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "medmate.observability.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SloComplianceSnapshotJob {

  private final IncidentService incidents;

  public SloComplianceSnapshotJob(IncidentService incidents) {
    this.incidents = incidents;
  }

  /** 00:05 on the 1st of each month, Asia/Kolkata. */
  @Scheduled(cron = "0 5 0 1 * *", zone = "Asia/Kolkata")
  public void run() {
    incidents.runMonthlySloSnapshot();
  }
}
