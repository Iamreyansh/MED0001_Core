package com.nammamedmate.prescription.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 15 minutes Asia/Kolkata: AWAITING_AUDIT past deadline → OVERDUE_AUDIT + alert. */
@Component
@ConditionalOnProperty(
    name = "medmate.prescription.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RxComplianceOverdueScheduler {

  private static final Logger log = LoggerFactory.getLogger(RxComplianceOverdueScheduler.class);

  private final RxComplianceAuditService service;

  public RxComplianceOverdueScheduler(RxComplianceAuditService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Kolkata")
  public void scanOverdueAudits() {
    int n = service.markOverdueAudits();
    if (n > 0) {
      log.info("Marked {} Rx audits OVERDUE_AUDIT", n);
    }
  }
}
