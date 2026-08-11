package com.nammamedmate.medicine_schedule.application.port.out;

import java.util.UUID;

/** Recalculates / cancels ReminderSchedule rows for a medicine (STORY-003). */
public interface ReminderRecalcPort {

  /** Rebuild next-7-day reminders; returns count scheduled. */
  int recalculate(UUID medicineId);

  /** Cancel future reminders; returns count cancelled. */
  int cancelFuture(UUID medicineId);
}
