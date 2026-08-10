package com.nammamedmate.prescription.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 5 minutes: WhatsApp owner alert for PENDING_REVIEW past 2h SLA (once). */
@Component
@ConditionalOnProperty(
    name = "medmate.prescription.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PharmacyRxOverdueScheduler {

  private static final Logger log = LoggerFactory.getLogger(PharmacyRxOverdueScheduler.class);

  private final PharmacyRxQueueService service;

  public PharmacyRxOverdueScheduler(PharmacyRxQueueService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${medmate.prescription.overdue-scan-ms:300000}")
  public void scanOverdue() {
    int n = service.notifyOverdue();
    if (n > 0) {
      log.info("Notified pharmacy owners for {} overdue Rx queue entries", n);
    }
  }
}
