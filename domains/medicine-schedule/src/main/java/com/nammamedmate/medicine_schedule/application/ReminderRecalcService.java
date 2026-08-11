package com.nammamedmate.medicine_schedule.application;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.DoseLogRecord;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderRecalcPort;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderScheduleStore;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderScheduleStore.ReminderRecord;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore.ScheduleMedicineRecord;
import com.nammamedmate.medicine_schedule.domain.DoseSlot;
import com.nammamedmate.medicine_schedule.domain.ReminderChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Real ReminderRecalcPort: upserts DoseLog + ReminderSchedule for a rolling 7-day window. */
@Service
public class ReminderRecalcService implements ReminderRecalcPort {

  public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  public static final int DEFAULT_DAYS = 7;

  private final ScheduleMedicineStore medicines;
  private final DoseLogStore doseLogs;
  private final ReminderScheduleStore reminders;
  private final Clock clock;

  public ReminderRecalcService(
      ScheduleMedicineStore medicines,
      DoseLogStore doseLogs,
      ReminderScheduleStore reminders,
      Clock clock) {
    this.medicines = medicines;
    this.doseLogs = doseLogs;
    this.reminders = reminders;
    this.clock = clock;
  }

  @Override
  @Transactional
  public int recalculate(UUID medicineId) {
    return recalculateWindow(medicineId, DEFAULT_DAYS).scheduled();
  }

  @Transactional
  public RecalcResult recalculateWindow(UUID medicineId, int daysAhead) {
    Optional<ScheduleMedicineRecord> opt = medicines.findById(medicineId);
    if (opt.isEmpty()) {
      return new RecalcResult(0, 0);
    }
    ScheduleMedicineRecord medicine = opt.get();
    Instant now = clock.instant();
    if (!eligibleForReminders(medicine)) {
      int cancelled = reminders.cancelFutureScheduled(medicineId, now);
      return new RecalcResult(0, cancelled);
    }

    int days = Math.max(1, Math.min(daysAhead, 14));
    LocalDate today = LocalDate.ofInstant(now, IST);
    List<String> keepSlots = medicine.doseSlots().stream().map(DoseSlot::slot).toList();
    int cancelled = reminders.cancelFutureNotInSlots(medicineId, keepSlots, now);

    int scheduled = 0;
    for (int d = 0; d < days; d++) {
      LocalDate doseDate = today.plusDays(d);
      if (medicine.endedOnDate() != null && doseDate.isAfter(medicine.endedOnDate())) {
        break;
      }
      if (doseDate.isBefore(medicine.startedOnDate())) {
        continue;
      }
      for (DoseSlot slot : medicine.doseSlots()) {
        LocalTime reminderTime = LocalTime.parse(slot.reminderTime());
        Instant scheduledAt = toUtcInstant(doseDate, reminderTime);
        DoseLogRecord log =
            doseLogs.upsertUpcoming(
                new DoseLogRecord(
                    Ids.newId(),
                    medicine.id(),
                    medicine.customerId(),
                    medicine.memberId(),
                    doseDate,
                    slot.slot(),
                    reminderTime,
                    "UPCOMING",
                    null,
                    false,
                    now,
                    now));
        reminders.upsertScheduled(
            new ReminderRecord(
                Ids.newId(),
                medicine.id(),
                medicine.customerId(),
                log.id(),
                scheduledAt,
                ReminderChannel.PUSH.name(),
                "SCHEDULED",
                null,
                null,
                null,
                null,
                now));
        scheduled++;
      }
    }
    return new RecalcResult(scheduled, cancelled);
  }

  @Override
  @Transactional
  public int cancelFuture(UUID medicineId) {
    return reminders.cancelFutureScheduled(medicineId, clock.instant());
  }

  /** Active + (units available OR supply tracking disabled via refill_remind_at_units = 0). */
  public static boolean eligibleForReminders(ScheduleMedicineRecord medicine) {
    if (medicine == null || !medicine.active()) {
      return false;
    }
    return medicine.unitsInHand() > 0 || medicine.refillRemindAtUnits() == 0;
  }

  public static Instant toUtcInstant(LocalDate doseDate, LocalTime reminderTime) {
    return ZonedDateTime.of(doseDate, reminderTime, IST).toInstant();
  }

  public record RecalcResult(int scheduled, int cancelled) {}
}
