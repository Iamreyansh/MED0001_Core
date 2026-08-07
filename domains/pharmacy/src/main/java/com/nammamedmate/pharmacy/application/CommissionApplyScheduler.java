package com.nammamedmate.pharmacy.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily 00:01 IST — apply commission changes whose effective_from is today. */
@Component
@ConditionalOnProperty(
    name = "medmate.pharmacy.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CommissionApplyScheduler {

  private final CommissionApplyService service;

  public CommissionApplyScheduler(CommissionApplyService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 1 0 * * *", zone = "Asia/Kolkata")
  public void applyDueCommissionChanges() {
    service.applyDueChanges();
  }
}
