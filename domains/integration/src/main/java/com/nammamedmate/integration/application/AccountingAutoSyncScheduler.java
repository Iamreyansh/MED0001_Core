package com.nammamedmate.integration.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Queues due auto-syncs (DAILY 02:00 IST / WEEKLY Mon) and drains the in-process job queue. */
@Component
@ConditionalOnProperty(
    name = "medmate.integration.accounting-auto-sync.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AccountingAutoSyncScheduler {

  private final AccountingService service;

  public AccountingAutoSyncScheduler(AccountingService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${medmate.integration.accounting-auto-sync-delay-ms:60000}")
  public void tick() {
    service.runDueAutoSyncs();
    service.processQueuedJobs();
  }
}
