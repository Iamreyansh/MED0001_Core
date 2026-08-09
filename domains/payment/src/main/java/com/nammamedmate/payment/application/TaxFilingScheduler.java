package com.nammamedmate.payment.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Monthly GSTR-8 / quarterly TDS filing rows + OVERDUE flip (EPIC-012 STORY-007). */
@Component
@ConditionalOnProperty(
    name = "medmate.payment.tax.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TaxFilingScheduler {

  private final TaxFacadeService taxes;

  public TaxFilingScheduler(TaxFacadeService taxes) {
    this.taxes = taxes;
  }

  /** 00:05 IST daily — create missing filings for prior periods; mark overdue. */
  @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Kolkata")
  public void maintainFilings() {
    taxes.runScheduledMaintenance();
  }
}
