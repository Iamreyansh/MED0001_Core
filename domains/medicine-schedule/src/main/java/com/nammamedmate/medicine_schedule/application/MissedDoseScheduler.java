package com.nammamedmate.medicine_schedule.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 02:00 IST — mark overdue UPCOMING dose logs as MISSED. */
@Component
@ConditionalOnProperty(
    name = "medmate.medicine-schedule.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class MissedDoseScheduler {

  private static final Logger log = LoggerFactory.getLogger(MissedDoseScheduler.class);

  private final DoseReminderService doses;

  public MissedDoseScheduler(DoseReminderService doses) {
    this.doses = doses;
  }

  @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
  public void markMissed() {
    int n = doses.markMissedDoses();
    if (n > 0) {
      log.info("Marked {} dose logs as MISSED", n);
    }
  }
}
