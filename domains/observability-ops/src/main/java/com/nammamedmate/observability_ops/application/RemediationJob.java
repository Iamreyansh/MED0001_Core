package com.nammamedmate.observability_ops.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "medmate.observability.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RemediationJob {

  private final RemediationService remediation;

  public RemediationJob(RemediationService remediation) {
    this.remediation = remediation;
  }

  /** Every minute at second 15, Asia/Kolkata. */
  @Scheduled(cron = "15 * * * * *", zone = "Asia/Kolkata")
  public void run() {
    remediation.runAutoCycle();
  }
}
