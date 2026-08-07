package com.nammamedmate.catalogue.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly price-ceiling violation UPSERT at 03:00 IST. */
@Component
@ConditionalOnProperty(
    name = "medmate.catalogue.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PriceCeilingViolationScheduler {

  private final PriceCeilingService service;

  public PriceCeilingViolationScheduler(PriceCeilingService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Kolkata")
  public void detectViolations() {
    service.detectViolations();
  }
}
