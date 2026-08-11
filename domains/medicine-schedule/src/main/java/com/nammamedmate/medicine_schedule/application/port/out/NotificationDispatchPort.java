package com.nammamedmate.medicine_schedule.application.port.out;

import java.util.UUID;

/** Outbox-only push for dose reminders and refill alerts (ids-only payload; no PII). */
public interface NotificationDispatchPort {

  void notifyDoseReminderDue(UUID customerId, UUID reminderId, UUID doseLogId, UUID medicineId);

  void notifyRefillAlert(
      UUID customerId, UUID medicineId, int unitsInHand, int refillRemindAtUnits);
}
