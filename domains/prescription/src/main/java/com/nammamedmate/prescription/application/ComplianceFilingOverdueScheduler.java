package com.nammamedmate.prescription.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Midnight IST daily: PENDING past due → OVERDUE + email; 3-day escalation. */
@Component
@ConditionalOnProperty(
    name = "medmate.prescription.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ComplianceFilingOverdueScheduler {

  private static final Logger log = LoggerFactory.getLogger(ComplianceFilingOverdueScheduler.class);

  private final ComplianceFilingService service;

  public ComplianceFilingOverdueScheduler(ComplianceFilingService service) {
    this.service = service;
  }

  /** Story: `0 0 * * *` (midnight IST daily) → Spring 6-field with seconds. */
  @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
  public void processOverdue() {
    int n = service.processOverdueFilings();
    if (n > 0) {
      log.info("Marked {} compliance filings OVERDUE", n);
    }
  }
}
