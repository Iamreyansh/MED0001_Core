package com.nammamedmate.order.application;

import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly pharmacy directory metrics refresh from live orders (EPIC-004). */
@Component
@ConditionalOnProperty(
    name = "medmate.order.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FillRateRefreshScheduler {

  private final PharmacyCandidatePort pharmacies;

  public FillRateRefreshScheduler(PharmacyCandidatePort pharmacies) {
    this.pharmacies = pharmacies;
  }

  @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Kolkata")
  public void refreshFillRates() {
    pharmacies.refreshFillRatesFromDirectoryMetrics();
  }
}
