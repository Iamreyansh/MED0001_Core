package com.nammamedmate.integration.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reset {@code daily_sent_count} at midnight Asia/Kolkata. */
@Component
@ConditionalOnProperty(
    name = "medmate.integration.comms-daily-reset.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CommunicationDailyResetScheduler {

  private final CommunicationService service;

  public CommunicationDailyResetScheduler(CommunicationService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
  public void midnightReset() {
    service.resetDailySentCounts();
  }
}
