package com.nammamedmate.prescription.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 2 AM IST on 1 Jan: archive filings with period_to older than 5 years. */
@Component
@ConditionalOnProperty(
    name = "medmate.prescription.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ComplianceFilingArchivalScheduler {

  private static final Logger log =
      LoggerFactory.getLogger(ComplianceFilingArchivalScheduler.class);

  private final ComplianceFilingService service;

  public ComplianceFilingArchivalScheduler(ComplianceFilingService service) {
    this.service = service;
  }

  /** Story: `0 2 1 1 *` (2 AM IST on 1 Jan) → Spring 6-field with seconds. */
  @Scheduled(cron = "0 0 2 1 1 *", zone = "Asia/Kolkata")
  public void archiveOldFilings() {
    int n = service.archiveOldFilings();
    if (n > 0) {
      log.info("Archived {} compliance filings older than 5 years", n);
    }
  }
}
