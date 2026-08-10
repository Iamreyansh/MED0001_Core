package com.nammamedmate.prescription.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily midnight IST: mark UPLOADED/E_PRESCRIPTION past expires_at as EXPIRED. */
@Component
@ConditionalOnProperty(
    name = "medmate.prescription.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PrescriptionExpiryScheduler {

  private static final Logger log = LoggerFactory.getLogger(PrescriptionExpiryScheduler.class);

  private final PrescriptionService service;

  public PrescriptionExpiryScheduler(PrescriptionService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
  public void expireDuePrescriptions() {
    int n = service.expireDue();
    if (n > 0) {
      log.info("Marked {} prescriptions EXPIRED", n);
    }
  }
}
