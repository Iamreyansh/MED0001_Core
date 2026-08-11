package com.nammamedmate.medicine_schedule.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.medicine_schedule.application.ReminderRecalcService.RecalcResult;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore.MemberRecord;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.DoseLogRecord;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.TodayCounts;
import com.nammamedmate.medicine_schedule.application.port.out.NotificationDispatchPort;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderScheduleStore;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderScheduleStore.ReminderRecord;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore.ScheduleMedicineRecord;
import com.nammamedmate.medicine_schedule.domain.DoseLogStatus;
import com.nammamedmate.medicine_schedule.domain.DoseSlotName;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DoseReminderService {

  private static final ZoneId IST = ReminderRecalcService.IST;
  private static final DateTimeFormatter TIME_LABEL =
      DateTimeFormatter.ofPattern("h:mm a", Locale.US);

  private final ReminderRecalcService recalc;
  private final ScheduleMedicineStore medicines;
  private final DoseLogStore doseLogs;
  private final ReminderScheduleStore reminders;
  private final CareCircleMemberStore members;
  private final CareCircleService careCircle;
  private final NotificationDispatchPort notifications;
  private final Clock clock;

  public DoseReminderService(
      ReminderRecalcService recalc,
      ScheduleMedicineStore medicines,
      DoseLogStore doseLogs,
      ReminderScheduleStore reminders,
      CareCircleMemberStore members,
      CareCircleService careCircle,
      NotificationDispatchPort notifications,
      Clock clock) {
    this.recalc = recalc;
    this.medicines = medicines;
    this.doseLogs = doseLogs;
    this.reminders = reminders;
    this.members = members;
    this.careCircle = careCircle;
    this.notifications = notifications;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> bulkSchedule(UUID customerId, Integer daysAhead) {
    if (customerId == null) {
      throw new AppException("VALIDATION_ERROR", "customer_id is required", 400);
    }
    int days = daysAhead == null ? ReminderRecalcService.DEFAULT_DAYS : daysAhead;
    if (days < 1 || days > 14) {
      throw new AppException("VALIDATION_ERROR", "days_ahead must be 1-14", 400);
    }
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, IST);
    int created = 0;
    int cancelled = 0;
    for (ScheduleMedicineRecord med : medicines.listActiveByCustomer(customerId)) {
      RecalcResult result = recalc.recalculateWindow(med.id(), days);
      created += result.scheduled();
      cancelled += result.cancelled();
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("customer_id", customerId);
    data.put("reminders_created", created);
    data.put("reminders_cancelled", cancelled);
    data.put("scheduled_through", today.plusDays(days - 1L).toString());
    data.put("processed_at", now);
    return data;
  }

  @Transactional
  public int bulkScheduleAllCustomers() {
    int total = 0;
    for (UUID customerId : medicines.listCustomerIdsWithActiveMedicines()) {
      Map<String, Object> result = bulkSchedule(customerId, ReminderRecalcService.DEFAULT_DAYS);
      total += (Integer) result.get("reminders_created");
    }
    return total;
  }

  @Transactional
  public int markMissedDoses() {
    Instant now = clock.instant();
    Instant cutoff = now.minus(Duration.ofHours(2));
    return doseLogs.markMissedBefore(cutoff, now);
  }

  @Transactional
  public int dispatchDueReminders(int limit) {
    Instant now = clock.instant();
    int sent = 0;
    for (ReminderRecord due : reminders.findDueScheduled(now, limit)) {
      String notificationId = com.nammamedmate.kernel.id.Ids.newId().toString();
      notifications.notifyDoseReminderDue(
          due.customerId(), due.id(), due.doseLogId(), due.medicineId());
      reminders.markSent(due.id(), now, notificationId);
      sent++;
    }
    return sent;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> today(MedmatePrincipal principal, UUID memberId) {
    UUID customerId = requireCustomerId(principal);
    MemberRecord member = resolveMember(customerId, memberId);
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    List<DoseLogRecord> logs = doseLogs.listByMemberAndDate(member.id(), today);

    Map<LocalTime, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
    for (DoseLogRecord log : logs) {
      ScheduleMedicineRecord med = medicines.findById(log.medicineId()).orElse(null);
      Map<String, Object> dose = new LinkedHashMap<>();
      dose.put("dose_log_id", log.id());
      dose.put("medicine_id", log.medicineId());
      dose.put("medicine_name", med == null ? null : med.medicineName());
      dose.put("strength", med == null ? null : med.strength());
      dose.put("dose", med == null ? null : med.dose());
      dose.put("form", med == null ? null : med.form());
      dose.put("food_instruction", med == null ? null : med.foodInstruction());
      dose.put("slot", log.slot());
      dose.put("status", log.status());
      dose.put("taken_at", log.takenAt());
      grouped.computeIfAbsent(log.reminderTime(), t -> new ArrayList<>()).add(dose);
    }

    List<Map<String, Object>> doseGroups = new ArrayList<>();
    for (Map.Entry<LocalTime, List<Map<String, Object>>> e : grouped.entrySet()) {
      Map<String, Object> group = new LinkedHashMap<>();
      group.put("time_label", e.getKey().format(TIME_LABEL));
      group.put("reminder_time", e.getKey().toString().substring(0, 5));
      group.put("doses", e.getValue());
      doseGroups.add(group);
    }

    TodayCounts counts = doseLogs.countsForMemberOn(member.id(), today);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("total", counts.total());
    summary.put("taken", counts.taken());
    summary.put("skipped", counts.skipped());
    summary.put("missed", counts.missed());
    summary.put("upcoming", counts.upcoming());

    Map<String, Object> memberView = new LinkedHashMap<>();
    memberView.put("member_id", member.id());
    memberView.put("name", member.name());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("member", memberView);
    data.put("date", today.toString());
    data.put("dose_groups", doseGroups);
    data.put("summary", summary);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> upcoming(
      MedmatePrincipal principal, UUID memberId, Integer hoursAhead) {
    UUID customerId = requireCustomerId(principal);
    MemberRecord member = resolveMember(customerId, memberId);
    int hours = hoursAhead == null ? 24 : hoursAhead;
    if (hours < 1 || hours > 48) {
      throw new AppException("VALIDATION_ERROR", "hours_ahead must be 1-48", 400);
    }
    Instant now = clock.instant();
    Instant until = now.plus(Duration.ofHours(hours));
    List<DoseLogRecord> logs = doseLogs.listUpcomingByMemberUntil(member.id(), until);

    List<Map<String, Object>> upcoming = new ArrayList<>();
    for (DoseLogRecord log : logs) {
      Instant scheduledAt = ReminderRecalcService.toUtcInstant(log.doseDate(), log.reminderTime());
      if (scheduledAt.isBefore(now)) {
        continue;
      }
      ScheduleMedicineRecord med = medicines.findById(log.medicineId()).orElse(null);
      double hoursUntil = Duration.between(now, scheduledAt).toMillis() / 3_600_000.0;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("dose_log_id", log.id());
      row.put("medicine_name", med == null ? null : med.medicineName());
      row.put("dose", med == null ? null : med.dose());
      row.put("slot", log.slot());
      row.put("scheduled_at", scheduledAt.atZone(IST).toOffsetDateTime().toString());
      row.put("status", log.status());
      row.put("hours_until", Math.round(hoursUntil * 100.0) / 100.0);
      upcoming.add(row);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("upcoming_doses", upcoming);
    data.put("count", upcoming.size());
    return data;
  }

  @Transactional
  public Map<String, Object> markDose(
      MedmatePrincipal principal,
      UUID medicineId,
      String dateRaw,
      String slotRaw,
      String statusRaw,
      Instant takenAtInput) {
    UUID customerId = requireCustomerId(principal);
    ScheduleMedicineRecord medicine =
        medicines
            .findById(medicineId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if (!medicine.customerId().equals(customerId)) {
      throw new AppException(
          "MEDICINE_ACCESS_DENIED", "Medicine does not belong to this customer", 403);
    }

    DoseLogStatus markStatus;
    try {
      markStatus = DoseLogStatus.parseMarkStatus(statusRaw);
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_STATUS", "status must be TAKEN or SKIPPED", 400);
    }

    LocalDate doseDate;
    try {
      doseDate = LocalDate.parse(dateRaw);
    } catch (Exception ex) {
      throw new AppException("VALIDATION_ERROR", "date must be YYYY-MM-DD", 400);
    }

    String slot;
    try {
      slot = DoseSlotName.parse(slotRaw).name();
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
    }

    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, IST);
    LocalDate yesterday = today.minusDays(1);
    if (doseDate.isBefore(yesterday)) {
      throw new AppException(
          "DOSE_LOG_LOCKED", "Dose log is locked and can no longer be modified", 400);
    }
    if (doseDate.isAfter(today)) {
      throw new AppException("DOSE_LOG_LOCKED", "Future doses cannot be marked yet", 400);
    }

    DoseLogRecord log =
        doseLogs
            .findByMedicineDateSlot(medicineId, doseDate, slot)
            .orElseThrow(
                () -> new AppException("DOSE_LOG_NOT_FOUND", "No scheduled dose found", 404));

    Instant scheduledAt = ReminderRecalcService.toUtcInstant(log.doseDate(), log.reminderTime());
    if (doseDate.isBefore(today) && Duration.between(scheduledAt, now).toHours() > 24) {
      throw new AppException(
          "DOSE_LOG_LOCKED", "Dose log is locked and can no longer be modified", 400);
    }

    Instant takenAt =
        markStatus == DoseLogStatus.TAKEN ? (takenAtInput == null ? now : takenAtInput) : null;
    DoseLogRecord updated = doseLogs.updateStatus(log.id(), markStatus.name(), takenAt, false, now);
    if (markStatus == DoseLogStatus.TAKEN) {
      medicines.decrementUnitsInHand(medicineId, now);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("dose_log_id", updated.id());
    data.put("medicine_name", medicine.medicineName());
    data.put("date", doseDate.toString());
    data.put("slot", updated.slot());
    data.put("status", updated.status());
    data.put("taken_at", updated.takenAt());
    return data;
  }

  private MemberRecord resolveMember(UUID customerId, UUID memberId) {
    if (memberId == null) {
      return members.findSelf(customerId).orElseGet(() -> careCircle.ensureSelf(customerId));
    }
    MemberRecord member =
        members
            .findById(memberId)
            .orElseThrow(() -> new AppException("MEMBER_NOT_FOUND", "Member not found", 404));
    if (!member.customerId().equals(customerId)) {
      throw new AppException(
          "MEMBER_ACCESS_DENIED", "Member does not belong to this customer", 403);
    }
    return member;
  }

  private static UUID requireCustomerId(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    return principal.subject();
  }
}
