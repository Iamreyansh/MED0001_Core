package com.nammamedmate.integration.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** ponytail: in-process poller for QUEUED accounting sync jobs (upgrade → SQS worker). */
@Component
@ConditionalOnProperty(
    name = "medmate.integration.accounting.sync-poller.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AccountingSyncJobProcessor {

  private final AccountingService service;

  public AccountingSyncJobProcessor(AccountingService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${medmate.integration.accounting.sync-poll-delay-ms:2000}")
  public void pollQueuedJobs() {
    service.processQueuedJobs();
  }
}
