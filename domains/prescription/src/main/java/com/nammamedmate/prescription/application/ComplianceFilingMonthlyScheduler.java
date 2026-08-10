package com.nammamedmate.prescription.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 9 AM IST on the 1st: create prior-month Schedule H1 + X filing calendar entries. */
@Component
@ConditionalOnProperty(
    name = "medmate.prescription.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ComplianceFilingMonthlyScheduler {

  private static final Logger log = LoggerFactory.getLogger(ComplianceFilingMonthlyScheduler.class);

  private final ComplianceFilingService service;

  public ComplianceFilingMonthlyScheduler(ComplianceFilingService service) {
    this.service = service;
  }

  /** Story: `0 9 1 * *` (9 AM IST on the 1st) → Spring 6-field with seconds. */
  @Scheduled(cron = "0 0 9 1 * *", zone = "Asia/Kolkata")
  public void createMonthlyFilings() {
    int n = service.createMonthlyFilings();
    if (n > 0) {
      log.info("Created {} monthly compliance filings", n);
    }
  }
}
