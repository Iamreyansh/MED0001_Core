package com.nammamedmate.medicine_schedule.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReminderScheduleStore {

  /** Upsert SCHEDULED reminder for dose_log (idempotent on dose_log_id). */
  ReminderRecord upsertScheduled(ReminderRecord draft);

  Optional<ReminderRecord> findByDoseLogId(UUID doseLogId);

  /** Cancel future SCHEDULED reminders for a medicine (scheduled_at >= now). Returns count. */
  int cancelFutureScheduled(UUID medicineId, Instant now);

  /** Cancel SCHEDULED reminders for medicine whose dose_log slot is not in keepSlots. */
  int cancelFutureNotInSlots(UUID medicineId, List<String> keepSlots, Instant now);

  List<ReminderRecord> findDueScheduled(Instant now, int limit);

  void markSent(UUID reminderId, Instant sentAt, String notificationId);

  record ReminderRecord(
      UUID id,
      UUID medicineId,
      UUID customerId,
      UUID doseLogId,
      Instant scheduledAt,
      String channel,
      String status,
      String notificationId,
      Instant sentAt,
      Instant deliveredAt,
      Instant openedAt,
      Instant createdAt) {}
}
