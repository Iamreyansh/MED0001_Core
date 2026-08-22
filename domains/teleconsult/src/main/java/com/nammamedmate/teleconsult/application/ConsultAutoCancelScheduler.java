package com.nammamedmate.teleconsult.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cancels scheduled consults still REQUESTED/DOCTOR_REVIEWING after scheduled_at + 30 minutes. */
@Component
@ConditionalOnProperty(
    name = "medmate.teleconsult.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ConsultAutoCancelScheduler {

  private static final Logger log = LoggerFactory.getLogger(ConsultAutoCancelScheduler.class);

  private final ConsultService service;

  public ConsultAutoCancelScheduler(ConsultService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${medmate.teleconsult.auto-cancel.delay-ms:60000}")
  public void autoCancelOverdue() {
    int assigned = service.assignDueScheduled();
    if (assigned > 0) {
      log.info("Assigned {} scheduled consults at slot time", assigned);
    }
    int n = service.autoCancelOverdue();
    if (n > 0) {
      log.info("Auto-cancelled {} overdue scheduled consults", n);
    }
  }
}
