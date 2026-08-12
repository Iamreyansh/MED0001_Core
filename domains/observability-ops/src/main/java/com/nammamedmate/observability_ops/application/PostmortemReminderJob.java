package com.nammamedmate.observability_ops.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "medmate.observability.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PostmortemReminderJob {

  private final IncidentService incidents;

  public PostmortemReminderJob(IncidentService incidents) {
    this.incidents = incidents;
  }

  /** Hourly at :00, Asia/Kolkata. */
  @Scheduled(cron = "0 0 * * * *", zone = "Asia/Kolkata")
  public void run() {
    incidents.runPostmortemReminders();
  }
}
