package com.nammamedmate.analytics.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-process report job poller + schedule runner.
 *
 * <p>ponytail: upgrade → outbox event + SQS worker handler when apps/worker report consumer is
 * ready (ceiling: single-node poller).
 */
@Component
@ConditionalOnProperty(
    name = "medmate.analytics.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AdminReportJobProcessor {

  private final ReportLibraryService reports;

  public AdminReportJobProcessor(ReportLibraryService reports) {
    this.reports = reports;
  }

  @Scheduled(fixedDelayString = "${medmate.analytics.reports.poll-ms:2000}")
  public void pollQueued() {
    reports.processQueuedBatch(5);
  }

  /** DAILY / WEEKLY Mon / MONTHLY 1st at 06:00 IST — cron fires daily; due filter inside. */
  @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
  public void runSchedules() {
    reports.runDueSchedules();
  }
}
