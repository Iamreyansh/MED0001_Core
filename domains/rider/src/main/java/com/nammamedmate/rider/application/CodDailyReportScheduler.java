package com.nammamedmate.rider.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AC-007: daily COD reconciliation report at 11 PM IST (outbox → EPIC-012 stub). */
@Component
@ConditionalOnProperty(
    name = "medmate.rider.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CodDailyReportScheduler {

  private final CodReconciliationService cod;

  public CodDailyReportScheduler(CodReconciliationService cod) {
    this.cod = cod;
  }

  @Scheduled(cron = "0 0 23 * * *", zone = "Asia/Kolkata")
  public void generateDailyReport() {
    cod.publishDailyReport();
  }
}
