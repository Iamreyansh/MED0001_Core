package com.nammamedmate.inventory.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly reorder suggestion refresh at 02:00 Asia/Kolkata (idempotent per snapshot_date). */
@Component
@ConditionalOnProperty(
    name = "medmate.inventory.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ReorderSuggestionScheduler {

  private final PharmacyReorderService reorderService;

  public ReorderSuggestionScheduler(PharmacyReorderService reorderService) {
    this.reorderService = reorderService;
  }

  @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
  public void refreshNightly() {
    reorderService.refreshAllPharmacies();
  }
}
