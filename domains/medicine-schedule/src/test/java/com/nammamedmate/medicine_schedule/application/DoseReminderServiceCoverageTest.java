package com.nammamedmate.medicine_schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore.MemberRecord;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.DoseLogRecord;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.TodayCounts;
import com.nammamedmate.medicine_schedule.application.port.out.NotificationDispatchPort;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderScheduleStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore.ScheduleMedicineRecord;
import com.nammamedmate.medicine_schedule.domain.DoseSlot;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Branch coverage fill for DoseReminderService + ReminderRecalcService. */
class DoseReminderServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T01:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private ScheduleMedicineStore medicines;
  private DoseLogStore doseLogs;
  private ReminderScheduleStore reminders;
  private CareCircleMemberStore members;
  private CareCircleService careCircle;
  private NotificationDispatchPort notifications;
  private ReminderRecalcService recalc;
  private DoseReminderService service;
  private UUID customerId;
  private UUID memberId;
  private MedmatePrincipal customer;

  @BeforeEach
  void setUp() {
    medicines = mock(ScheduleMedicineStore.class);
    doseLogs = mock(DoseLogStore.class);
    reminders = mock(ReminderScheduleStore.class);
    members = mock(CareCircleMemberStore.class);
    careCircle = mock(CareCircleService.class);
    notifications = mock(NotificationDispatchPort.class);
    recalc = new ReminderRecalcService(medicines, doseLogs, reminders, CLOCK);
    service =
        new DoseReminderService(
            recalc, medicines, doseLogs, reminders, members, careCircle, notifications, CLOCK);
    customerId = Ids.newId();
    memberId = Ids.newId();
    customer = new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  }

  @Test
  void bulkScheduleDaysDefaultAndAllCustomers() {
    when(medicines.listActiveByCustomer(customerId)).thenReturn(List.of());
    Map<String, Object> data = service.bulkSchedule(customerId, null);
    assertThat(data.get("reminders_created")).isEqualTo(0);

    when(medicines.listCustomerIdsWithActiveMedicines()).thenReturn(List.of(customerId));
    assertThat(service.bulkScheduleAllCustomers()).isZero();
  }

  @Test
  void bulkScheduleDaysTooLow() {
    assertThatThrownBy(() -> service.bulkSchedule(customerId, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void todayWithMissingMedicineAndNullMemberUsesSelf() {
    MemberRecord self =
        new MemberRecord(
            memberId, customerId, "Priya", 30, "SELF", "👤", "#6B7280", true, NOW, NOW, null);
    when(members.findSelf(customerId)).thenReturn(Optional.empty());
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
    DoseLogRecord log =
        new DoseLogRecord(
            Ids.newId(),
            Ids.newId(),
            customerId,
            memberId,
            LocalDate.of(2026, 7, 24),
            "MORNING",
            LocalTime.of(8, 0),
            "UPCOMING",
            null,
            false,
            NOW,
            NOW);
    when(doseLogs.listByMemberAndDate(memberId, LocalDate.of(2026, 7, 24)))
        .thenReturn(List.of(log));
    when(medicines.findById(log.medicineId())).thenReturn(Optional.empty());
    when(doseLogs.countsForMemberOn(memberId, LocalDate.of(2026, 7, 24)))
        .thenReturn(new TodayCounts(1, 0, 0, 0, 1));
    Map<String, Object> data = service.today(customer, null);
    assertThat(data.get("date")).isEqualTo("2026-07-24");
  }

  @Test
  void upcomingValidationAndPastSkipped() {
    MemberRecord self =
        new MemberRecord(
            memberId, customerId, "Priya", 30, "SELF", "👤", "#6B7280", true, NOW, NOW, null);
    when(members.findSelf(customerId)).thenReturn(Optional.of(self));
    assertThatThrownBy(() -> service.upcoming(customer, null, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.upcoming(customer, null, 99))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    DoseLogRecord past =
        new DoseLogRecord(
            Ids.newId(),
            Ids.newId(),
            customerId,
            memberId,
            LocalDate.of(2026, 7, 23),
            "MORNING",
            LocalTime.of(8, 0),
            "UPCOMING",
            null,
            false,
            NOW,
            NOW);
    when(doseLogs.listUpcomingByMemberUntil(anyEq(memberId), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(past));
    when(medicines.findById(past.medicineId())).thenReturn(Optional.empty());
    Map<String, Object> data = service.upcoming(customer, null, null);
    assertThat(data.get("count")).isEqualTo(0);
  }

  private static UUID anyEq(UUID id) {
    return org.mockito.ArgumentMatchers.eq(id);
  }

  @Test
  void markDoseBranches() {
    ScheduleMedicineRecord med = medicine();
    when(medicines.findById(med.id())).thenReturn(Optional.of(med));

    assertThatThrownBy(
            () -> service.markDose(customer, Ids.newId(), "2026-07-24", "MORNING", "TAKEN", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");

    assertThatThrownBy(
            () -> service.markDose(customer, med.id(), "not-a-date", "MORNING", "TAKEN", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () -> service.markDose(customer, med.id(), "2026-07-24", "BAD", "TAKEN", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () -> service.markDose(customer, med.id(), "2026-07-25", "MORNING", "TAKEN", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOSE_LOG_LOCKED");

    DoseLogRecord yesterday =
        new DoseLogRecord(
            Ids.newId(),
            med.id(),
            customerId,
            memberId,
            LocalDate.of(2026, 7, 23),
            "MORNING",
            LocalTime.of(8, 0),
            "UPCOMING",
            null,
            false,
            NOW,
            NOW);
    when(doseLogs.findByMedicineDateSlot(med.id(), LocalDate.of(2026, 7, 23), "MORNING"))
        .thenReturn(Optional.of(yesterday));
    // scheduled 23rd 08:00 IST is >24h before NOW 24th 06:30 IST? NOW is 01:00 UTC = 06:30 IST
    // 23rd 08:00 IST = 22nd 26:30? 23 Jul 08:00 IST = 23 Jul 02:30 UTC
    // Duration from 02:30 UTC Jul23 to 01:00 UTC Jul24 = ~22.5h < 24h, so NOT locked by 24h rule
    // unless we use a later clock. Use clock after >24h:
    Clock late = Clock.fixed(Instant.parse("2026-07-24T10:00:00Z"), ZoneOffset.UTC); // 15:30 IST
    DoseReminderService lateService =
        new DoseReminderService(
            new ReminderRecalcService(medicines, doseLogs, reminders, late),
            medicines,
            doseLogs,
            reminders,
            members,
            careCircle,
            notifications,
            late);
    assertThatThrownBy(
            () ->
                lateService.markDose(customer, med.id(), "2026-07-23", "MORNING", "SKIPPED", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOSE_LOG_LOCKED");

    DoseLogRecord today =
        new DoseLogRecord(
            Ids.newId(),
            med.id(),
            customerId,
            memberId,
            LocalDate.of(2026, 7, 24),
            "NIGHT",
            LocalTime.of(21, 0),
            "UPCOMING",
            null,
            false,
            NOW,
            NOW);
    when(doseLogs.findByMedicineDateSlot(med.id(), LocalDate.of(2026, 7, 24), "NIGHT"))
        .thenReturn(Optional.of(today));
    Instant takenAt = Instant.parse("2026-07-24T15:30:00Z");
    when(doseLogs.updateStatus(today.id(), "TAKEN", takenAt, false, NOW))
        .thenReturn(
            new DoseLogRecord(
                today.id(),
                today.medicineId(),
                today.customerId(),
                today.memberId(),
                today.doseDate(),
                today.slot(),
                today.reminderTime(),
                "TAKEN",
                takenAt,
                false,
                today.createdAt(),
                NOW));
    when(medicines.decrementUnitsInHand(med.id(), NOW)).thenReturn(1);
    Map<String, Object> marked =
        service.markDose(customer, med.id(), "2026-07-24", "NIGHT", "TAKEN", takenAt);
    assertThat(marked.get("status")).isEqualTo("TAKEN");
  }

  @Test
  void resolveMemberDeniedAndUnauthorized() {
    UUID otherMember = Ids.newId();
    when(members.findById(otherMember))
        .thenReturn(
            Optional.of(
                new MemberRecord(
                    otherMember,
                    Ids.newId(),
                    "X",
                    1,
                    "CHILD",
                    "👤",
                    "#6B7280",
                    false,
                    NOW,
                    NOW,
                    null)));
    assertThatThrownBy(() -> service.today(customer, otherMember))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEMBER_ACCESS_DENIED");

    when(members.findById(otherMember)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.today(customer, otherMember))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEMBER_NOT_FOUND");

    assertThatThrownBy(() -> service.today(null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal pharmacy =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.today(pharmacy, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void recalcEdges() {
    assertThat(recalc.recalculateWindow(Ids.newId(), 7).scheduled()).isZero();
    assertThat(ReminderRecalcService.eligibleForReminders(null)).isFalse();

    ScheduleMedicineRecord ended =
        new ScheduleMedicineRecord(
            Ids.newId(),
            customerId,
            memberId,
            null,
            "Med",
            null,
            "1",
            "TABLET",
            List.of(new DoseSlot("MORNING", "08:00")),
            "ANY",
            "DAYS",
            1,
            LocalDate.of(2026, 7, 20),
            LocalDate.of(2026, 7, 21),
            null,
            null,
            10,
            5,
            null,
            true,
            NOW,
            NOW);
    when(medicines.findById(ended.id())).thenReturn(Optional.of(ended));
    when(reminders.cancelFutureNotInSlots(
            org.mockito.ArgumentMatchers.eq(ended.id()),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(0);
    assertThat(recalc.recalculateWindow(ended.id(), 7).scheduled()).isZero();

    ScheduleMedicineRecord futureStart =
        new ScheduleMedicineRecord(
            Ids.newId(),
            customerId,
            memberId,
            null,
            "Med",
            null,
            "1",
            "TABLET",
            List.of(new DoseSlot("MORNING", "08:00")),
            "ANY",
            "ONGOING",
            null,
            LocalDate.of(2026, 8, 1),
            null,
            null,
            null,
            10,
            5,
            null,
            true,
            NOW,
            NOW);
    when(medicines.findById(futureStart.id())).thenReturn(Optional.of(futureStart));
    when(reminders.cancelFutureNotInSlots(
            org.mockito.ArgumentMatchers.eq(futureStart.id()),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(0);
    when(doseLogs.upsertUpcoming(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> inv.getArgument(0));
    when(reminders.upsertScheduled(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> inv.getArgument(0));
    assertThat(recalc.recalculateWindow(futureStart.id(), 7).scheduled()).isZero();

    ScheduleMedicineRecord inactive =
        new ScheduleMedicineRecord(
            Ids.newId(),
            customerId,
            memberId,
            null,
            "Med",
            null,
            "1",
            "TABLET",
            List.of(new DoseSlot("MORNING", "08:00")),
            "ANY",
            "ONGOING",
            null,
            LocalDate.of(2026, 7, 24),
            null,
            null,
            null,
            10,
            5,
            null,
            false,
            NOW,
            NOW);
    assertThat(ReminderRecalcService.eligibleForReminders(inactive)).isFalse();
    when(medicines.findById(inactive.id())).thenReturn(Optional.of(inactive));
    when(reminders.cancelFutureScheduled(
            org.mockito.ArgumentMatchers.eq(inactive.id()), org.mockito.ArgumentMatchers.any()))
        .thenReturn(3);
    assertThat(recalc.recalculateWindow(inactive.id(), 7).cancelled()).isEqualTo(3);
  }

  @Test
  void upcomingWithMedicineAndSkipYesterdayWithin24h() {
    MemberRecord self =
        new MemberRecord(
            memberId, customerId, "Priya", 30, "SELF", "👤", "#6B7280", true, NOW, NOW, null);
    when(members.findSelf(customerId)).thenReturn(Optional.of(self));
    ScheduleMedicineRecord med = medicine();
    DoseLogRecord future =
        new DoseLogRecord(
            Ids.newId(),
            med.id(),
            customerId,
            memberId,
            LocalDate.of(2026, 7, 24),
            "NIGHT",
            LocalTime.of(21, 0),
            "UPCOMING",
            null,
            false,
            NOW,
            NOW);
    DoseLogRecord noMed =
        new DoseLogRecord(
            Ids.newId(),
            Ids.newId(),
            customerId,
            memberId,
            LocalDate.of(2026, 7, 24),
            "CUSTOM",
            LocalTime.of(22, 0),
            "UPCOMING",
            null,
            false,
            NOW,
            NOW);
    when(doseLogs.listUpcomingByMemberUntil(
            org.mockito.ArgumentMatchers.eq(memberId), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(future))
        .thenReturn(List.of(noMed));
    when(medicines.findById(med.id())).thenReturn(Optional.of(med));
    when(medicines.findById(noMed.medicineId())).thenReturn(Optional.empty());
    Map<String, Object> withMed = service.upcoming(customer, null, 24);
    assertThat(withMed.get("count")).isEqualTo(1);
    Map<String, Object> nullMed = service.upcoming(customer, null, 24);
    assertThat(nullMed.get("count")).isEqualTo(1);

    // yesterday morning within 24h of NOW (06:30 IST) — mark SKIPPED, no units decrement
    DoseLogRecord yesterday =
        new DoseLogRecord(
            Ids.newId(),
            med.id(),
            customerId,
            memberId,
            LocalDate.of(2026, 7, 23),
            "NIGHT",
            LocalTime.of(21, 0),
            "UPCOMING",
            null,
            false,
            NOW,
            NOW);
    when(doseLogs.findByMedicineDateSlot(med.id(), LocalDate.of(2026, 7, 23), "NIGHT"))
        .thenReturn(Optional.of(yesterday));
    when(doseLogs.updateStatus(yesterday.id(), "SKIPPED", null, false, NOW))
        .thenReturn(
            new DoseLogRecord(
                yesterday.id(),
                yesterday.medicineId(),
                yesterday.customerId(),
                yesterday.memberId(),
                yesterday.doseDate(),
                yesterday.slot(),
                yesterday.reminderTime(),
                "SKIPPED",
                null,
                false,
                yesterday.createdAt(),
                NOW));
    Map<String, Object> skipped =
        service.markDose(customer, med.id(), "2026-07-23", "NIGHT", "SKIPPED", null);
    assertThat(skipped.get("status")).isEqualTo("SKIPPED");

    // TAKEN with null takenAt uses clock now
    DoseLogRecord todayMorning =
        new DoseLogRecord(
            Ids.newId(),
            med.id(),
            customerId,
            memberId,
            LocalDate.of(2026, 7, 24),
            "MORNING",
            LocalTime.of(8, 0),
            "UPCOMING",
            null,
            false,
            NOW,
            NOW);
    when(doseLogs.findByMedicineDateSlot(med.id(), LocalDate.of(2026, 7, 24), "MORNING"))
        .thenReturn(Optional.of(todayMorning));
    when(doseLogs.updateStatus(todayMorning.id(), "TAKEN", NOW, false, NOW))
        .thenReturn(
            new DoseLogRecord(
                todayMorning.id(),
                todayMorning.medicineId(),
                todayMorning.customerId(),
                todayMorning.memberId(),
                todayMorning.doseDate(),
                todayMorning.slot(),
                todayMorning.reminderTime(),
                "TAKEN",
                NOW,
                false,
                todayMorning.createdAt(),
                NOW));
    when(medicines.decrementUnitsInHand(med.id(), NOW)).thenReturn(1);
    assertThat(
            service
                .markDose(customer, med.id(), "2026-07-24", "MORNING", "TAKEN", null)
                .get("status"))
        .isEqualTo("TAKEN");
  }

  @Test
  void recalcPartialEndDateWindow() {
    // ended mid-window: day0 schedules, day1+ breaks
    ScheduleMedicineRecord ended =
        new ScheduleMedicineRecord(
            Ids.newId(),
            customerId,
            memberId,
            null,
            "Med",
            null,
            "1",
            "TABLET",
            List.of(new DoseSlot("MORNING", "08:00")),
            "ANY",
            "DAYS",
            1,
            LocalDate.of(2026, 7, 24),
            LocalDate.of(2026, 7, 24),
            null,
            null,
            10,
            5,
            null,
            true,
            NOW,
            NOW);
    when(medicines.findById(ended.id())).thenReturn(Optional.of(ended));
    when(reminders.cancelFutureNotInSlots(
            org.mockito.ArgumentMatchers.eq(ended.id()),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(0);
    when(doseLogs.upsertUpcoming(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> inv.getArgument(0));
    when(reminders.upsertScheduled(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> inv.getArgument(0));
    assertThat(recalc.recalculateWindow(ended.id(), 7).scheduled()).isEqualTo(1);
  }

  private ScheduleMedicineRecord medicine() {
    return new ScheduleMedicineRecord(
        Ids.newId(),
        customerId,
        memberId,
        null,
        "Metformin",
        "500mg",
        "1 tablet",
        "TABLET",
        List.of(new DoseSlot("MORNING", "08:00"), new DoseSlot("NIGHT", "21:00")),
        "AFTER",
        "ONGOING",
        null,
        LocalDate.of(2026, 7, 24),
        null,
        null,
        null,
        30,
        10,
        null,
        true,
        NOW,
        NOW);
  }
}
