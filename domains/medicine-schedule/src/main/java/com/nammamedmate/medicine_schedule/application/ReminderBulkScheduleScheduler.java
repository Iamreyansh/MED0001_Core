package com.nammamedmate.medicine_schedule.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 00:00 IST — extend rolling 7-day reminder window for every customer with active meds. */
@Component
@ConditionalOnProperty(
    name = "medmate.medicine-schedule.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ReminderBulkScheduleScheduler {

  private static final Logger log = LoggerFactory.getLogger(ReminderBulkScheduleScheduler.class);

  private final DoseReminderService doses;

  public ReminderBulkScheduleScheduler(DoseReminderService doses) {
    this.doses = doses;
  }

  @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
  public void extendWindow() {
    int n = doses.bulkScheduleAllCustomers();
    if (n > 0) {
      log.info("Bulk-scheduled {} dose reminders across customers", n);
    }
  }
}
