package com.nammamedmate.crm.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
    name = "medmate.crm.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InvoiceDunningScheduler {

  private final SaasBillingService billing;

  public InvoiceDunningScheduler(SaasBillingService billing) {
    this.billing = billing;
  }

  /** Daily dunning step advancement (Day 0/3/7/10/14) in Asia/Kolkata. */
  @Scheduled(cron = "0 20 1 * * *", zone = "Asia/Kolkata")
  @Transactional
  public void run() {
    billing.processDunningJobs();
  }
}
