package com.nammamedmate.pharmacy.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Monday 06:00 IST — generate previous-week settlement records. */
@Component
@ConditionalOnProperty(
    name = "medmate.pharmacy.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SettlementGenerationScheduler {

  private final SettlementGenerationService service;

  public SettlementGenerationScheduler(SettlementGenerationService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 0 6 * * MON", zone = "Asia/Kolkata")
  public void generateWeeklySettlements() {
    service.generateWeeklySettlements();
  }
}
