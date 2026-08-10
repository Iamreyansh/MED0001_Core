package com.nammamedmate.prescription.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily Asia/Kolkata: mark register entries past retention_expires_at as archived. */
@Component
@ConditionalOnProperty(
    name = "medmate.prescription.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ScheduleDrugRegisterArchivalScheduler {

  private static final Logger log =
      LoggerFactory.getLogger(ScheduleDrugRegisterArchivalScheduler.class);

  private final ScheduleDrugRegisterService service;

  public ScheduleDrugRegisterArchivalScheduler(ScheduleDrugRegisterService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Kolkata")
  public void archiveExpiredEntries() {
    int n = service.archiveExpired();
    if (n > 0) {
      log.info("Archived {} schedule drug register entries past retention", n);
    }
  }
}
