package com.nammamedmate.observability_ops.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "medmate.observability.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class IncidentAutoCreateJob {

  private final IncidentService incidents;

  public IncidentAutoCreateJob(IncidentService incidents) {
    this.incidents = incidents;
  }

  /** Every minute at second 30, Asia/Kolkata (offset from other jobs). */
  @Scheduled(cron = "30 * * * * *", zone = "Asia/Kolkata")
  public void run() {
    incidents.runAutoCreate();
  }
}
