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
public class RenewalChurnScheduler {

  private final RenewalChurnService renewalChurn;

  public RenewalChurnScheduler(RenewalChurnService renewalChurn) {
    this.renewalChurn = renewalChurn;
  }

  /** Daily win-back trigger for accounts EXPIRED 7 days ago (Asia/Kolkata). */
  @Scheduled(cron = "0 30 1 * * *", zone = "Asia/Kolkata")
  @Transactional
  public void winback() {
    renewalChurn.processWinbacks();
  }

  /** Daily at-risk CSM alerts for health &lt; 50 renewing within 30d. */
  @Scheduled(cron = "0 40 1 * * *", zone = "Asia/Kolkata")
  @Transactional
  public void atRiskCsm() {
    renewalChurn.processAtRiskCsmAlerts();
  }

  /** Monthly churn report on the 1st at 02:00 Asia/Kolkata (prior month). */
  @Scheduled(cron = "0 0 2 1 * *", zone = "Asia/Kolkata")
  @Transactional
  public void monthlyReport() {
    renewalChurn.processMonthlyChurnReport();
  }
}
