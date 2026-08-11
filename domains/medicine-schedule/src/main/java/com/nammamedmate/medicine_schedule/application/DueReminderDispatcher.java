package com.nammamedmate.medicine_schedule.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically dispatch due SCHEDULED reminders via NotificationDispatchPort. */
@Component
@ConditionalOnProperty(
    name = "medmate.medicine-schedule.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DueReminderDispatcher {

  private static final Logger log = LoggerFactory.getLogger(DueReminderDispatcher.class);

  private final DoseReminderService doses;

  public DueReminderDispatcher(DoseReminderService doses) {
    this.doses = doses;
  }

  @Scheduled(fixedDelayString = "${medmate.medicine-schedule.due-dispatch-delay-ms:60000}")
  public void dispatchDue() {
    int n = doses.dispatchDueReminders(100);
    if (n > 0) {
      log.info("Dispatched {} due dose reminders", n);
    }
  }
}
