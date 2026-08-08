package com.nammamedmate.settings.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuditArchivalScheduler {

  private final AuditLogService auditLogService;

  public AuditArchivalScheduler(AuditLogService auditLogService) {
    this.auditLogService = auditLogService;
  }

  /** Daily archival of rows older than 2 years (Asia/Kolkata). */
  @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
  public void archiveOldEntries() {
    auditLogService.archiveOlderThanTwoYears();
  }
}
