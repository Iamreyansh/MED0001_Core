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
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.DailyCounts;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.DoseLogRecord;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.TodayCounts;
import com.nammamedmate.medicine_schedule.application.port.out.NotificationDispatchPort;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderScheduleStore;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderScheduleStore.ReminderRecord;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DoseReminderServiceTest {

  /** 2026-07-24 06:30 IST = 01:00 UTC — before MORNING 08:00 so all 14 reminders are future. */
  private static final Instant NOW = Instant.parse("2026-07-24T01:00:00Z");

  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private FakeMedicines medicines;
  private FakeDoseLogs doseLogs;
  private FakeReminders reminders;
  private FakeMembers members;
  private CareCircleService careCircle;
  private RecordingNotifications notifications;
  private ReminderRecalcService recalc;
  private DoseReminderService service;
  private UUID customerId;
  private UUID memberId;
  private MedmatePrincipal customer;

  @BeforeEach
  void setUp() {
    medicines = new FakeMedicines();
    doseLogs = new FakeDoseLogs();
    reminders = new FakeReminders();
    members = new FakeMembers();
    careCircle = mock(CareCircleService.class);
    notifications = new RecordingNotifications();
    recalc = new ReminderRecalcService(medicines, doseLogs, reminders, CLOCK);
    service =
        new DoseReminderService(
            recalc, medicines, doseLogs, reminders, members, careCircle, notifications, CLOCK);
    customerId = Ids.newId();
    memberId = Ids.newId();
    customer = new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    MemberRecord self =
        new MemberRecord(
            memberId,
            customerId,
            "Priya Sharma",
            30,
            "SELF",
            "👤",
            "#6B7280",
            true,
            NOW,
            NOW,
            null);
    members.insert(self);
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
  }

  @Test
  void ac_twoSlotsCreate14DoseLogsAnd14Reminders() {
    ScheduleMedicineRecord med = insertMedicine(twoSlots(), 30, 10);
    int scheduled = recalc.recalculate(med.id());
    assertThat(scheduled).isEqualTo(14);
    assertThat(doseLogs.all()).hasSize(14);
    assertThat(reminders.all()).hasSize(14);
    assertThat(reminders.all()).allMatch(r -> "SCHEDULED".equals(r.status()));
  }

  @Test
  void ac_markTakenSetsStatusAndTakenAt() {
    ScheduleMedicineRecord med = insertMedicine(twoSlots(), 30, 10);
    recalc.recalculate(med.id());
    Map<String, Object> data =
        service.markDose(customer, med.id(), "2026-07-24", "MORNING", "TAKEN", null);
    assertThat(data.get("status")).isEqualTo("TAKEN");
    assertThat(data.get("taken_at")).isEqualTo(NOW);
    assertThat(medicines.findById(med.id()).orElseThrow().unitsInHand()).isEqualTo(29);
  }

  @Test
  void ac_markOldDoseReturnsDoseLogLocked() {
    ScheduleMedicineRecord med = insertMedicine(twoSlots(), 30, 10);
    LocalDate old = LocalDate.of(2026, 7, 22);
    DoseLogRecord log =
        doseLogs.upsertUpcoming(
            new DoseLogRecord(
                Ids.newId(),
                med.id(),
                customerId,
                memberId,
                old,
                "MORNING",
                LocalTime.of(8, 0),
                "UPCOMING",
                null,
                false,
                NOW,
                NOW));
    reminders.upsertScheduled(
        new ReminderRecord(
            Ids.newId(),
            med.id(),
            customerId,
            log.id(),
            ReminderRecalcService.toUtcInstant(old, LocalTime.of(8, 0)),
            "PUSH",
            "SCHEDULED",
            null,
            null,
            null,
            null,
            NOW));

    assertThatThrownBy(
            () -> service.markDose(customer, med.id(), "2026-07-22", "MORNING", "TAKEN", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOSE_LOG_LOCKED");
  }

  @Test
  void ac_deleteCancelsFutureScheduledReminders() {
    ScheduleMedicineRecord med = insertMedicine(twoSlots(), 30, 10);
    recalc.recalculate(med.id());
    int cancelled = recalc.cancelFuture(med.id());
    assertThat(cancelled).isEqualTo(14);
    assertThat(reminders.all()).allMatch(r -> "CANCELLED".equals(r.status()));
  }

  @Test
  void ac_autoMissJobMarksOverdueUpcoming() {
    ScheduleMedicineRecord med = insertMedicine(twoSlots(), 30, 10);
    LocalDate yesterday = LocalDate.of(2026, 7, 23);
    doseLogs.upsertUpcoming(
        new DoseLogRecord(
            Ids.newId(),
            med.id(),
            customerId,
            memberId,
            yesterday,
            "MORNING",
            LocalTime.of(8, 0),
            "UPCOMING",
            null,
            false,
            NOW,
            NOW));
    int missed = service.markMissedDoses();
    assertThat(missed).isEqualTo(1);
    assertThat(doseLogs.all().getFirst().status()).isEqualTo("MISSED");
  }

  @Test
  void ac_todayGroupsByReminderTime() {
    ScheduleMedicineRecord med = insertMedicine(twoSlots(), 30, 10);
    recalc.recalculate(med.id());
    Map<String, Object> data = service.today(customer, memberId);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("dose_groups");
    assertThat(groups).hasSize(2);
    assertThat(groups.get(0).get("reminder_time")).isEqualTo("08:00");
    assertThat(groups.get(1).get("reminder_time")).isEqualTo("21:00");
  }

  @Test
  void ac_sameTimeMergeIntoOneGroup() {
    insertMedicine(List.of(new DoseSlot("MORNING", "08:00")), 30, 10);
    insertMedicine(List.of(new DoseSlot("MORNING", "08:00")), 20, 5);
    for (ScheduleMedicineRecord m : medicines.listActiveByCustomer(customerId)) {
      recalc.recalculate(m.id());
    }
    Map<String, Object> data = service.today(customer, memberId);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("dose_groups");
    assertThat(groups).hasSize(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> doses = (List<Map<String, Object>>) groups.get(0).get("doses");
    assertThat(doses).hasSize(2);
  }

  @Test
  void ac_dispatchMarksSent() {
    ScheduleMedicineRecord med = insertMedicine(twoSlots(), 30, 10);
    DoseLogRecord log =
        doseLogs.upsertUpcoming(
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
                NOW));
    reminders.upsertScheduled(
        new ReminderRecord(
            Ids.newId(),
            med.id(),
            customerId,
            log.id(),
            NOW.minusSeconds(60),
            "PUSH",
            "SCHEDULED",
            null,
            null,
            null,
            null,
            NOW));
    int sent = service.dispatchDueReminders(10);
    assertThat(sent).isEqualTo(1);
    assertThat(reminders.all().getFirst().status()).isEqualTo("SENT");
    assertThat(reminders.all().getFirst().sentAt()).isEqualTo(NOW);
    assertThat(notifications.calls.get()).isEqualTo(1);
  }

  @Test
  void bulkScheduleAndUpcoming() {
    insertMedicine(twoSlots(), 30, 10);
    Map<String, Object> bulk = service.bulkSchedule(customerId, 7);
    assertThat(bulk.get("reminders_created")).isEqualTo(14);
    assertThat(bulk.get("scheduled_through")).isEqualTo("2026-07-30");
    Map<String, Object> upcoming = service.upcoming(customer, null, 24);
    assertThat((Integer) upcoming.get("count")).isGreaterThan(0);
  }

  @Test
  void markInvalidStatusAndNotFound() {
    ScheduleMedicineRecord med = insertMedicine(twoSlots(), 30, 10);
    assertThatThrownBy(
            () -> service.markDose(customer, med.id(), "2026-07-24", "MORNING", "EATEN", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");
    assertThatThrownBy(
            () -> service.markDose(customer, med.id(), "2026-07-24", "MORNING", "TAKEN", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOSE_LOG_NOT_FOUND");
  }

  @Test
  void ineligibleMedicineCancelsReminders() {
    ScheduleMedicineRecord med = insertMedicine(twoSlots(), 0, 5);
    int cancelled = recalc.recalculate(med.id());
    assertThat(cancelled).isZero();
    assertThat(doseLogs.all()).isEmpty();
  }

  @Test
  void supplyTrackingDisabledStillSchedules() {
    ScheduleMedicineRecord med = insertMedicine(twoSlots(), 0, 0);
    assertThat(recalc.recalculate(med.id())).isEqualTo(14);
  }

  @Test
  void bulkScheduleValidation() {
    assertThatThrownBy(() -> service.bulkSchedule(null, 7))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.bulkSchedule(customerId, 99))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void accessDeniedOnForeignMedicine() {
    ScheduleMedicineRecord med = insertMedicine(twoSlots(), 30, 10);
    MedmatePrincipal other =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () -> service.markDose(other, med.id(), "2026-07-24", "MORNING", "SKIPPED", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_ACCESS_DENIED");
  }

  private ScheduleMedicineRecord insertMedicine(List<DoseSlot> slots, int units, int refillAt) {
    ScheduleMedicineRecord med =
        new ScheduleMedicineRecord(
            Ids.newId(),
            customerId,
            memberId,
            null,
            "Metformin 500mg",
            "500mg",
            "1 tablet",
            "TABLET",
            slots,
            "AFTER",
            "ONGOING",
            null,
            LocalDate.of(2026, 7, 24),
            null,
            null,
            null,
            units,
            refillAt,
            null,
            true,
            NOW,
            NOW);
    return medicines.insert(med);
  }

  private static List<DoseSlot> twoSlots() {
    return List.of(new DoseSlot("MORNING", "08:00"), new DoseSlot("NIGHT", "21:00"));
  }

  private static final class RecordingNotifications implements NotificationDispatchPort {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public void notifyDoseReminderDue(
        UUID customerId, UUID reminderId, UUID doseLogId, UUID medicineId) {
      calls.incrementAndGet();
    }

    @Override
    public void notifyRefillAlert(
        UUID customerId, UUID medicineId, int unitsInHand, int refillRemindAtUnits) {}
  }

  private static final class FakeMedicines implements ScheduleMedicineStore {
    private final Map<UUID, ScheduleMedicineRecord> byId = new ConcurrentHashMap<>();

    @Override
    public ScheduleMedicineRecord insert(ScheduleMedicineRecord medicine) {
      byId.put(medicine.id(), medicine);
      return medicine;
    }

    @Override
    public ScheduleMedicineRecord update(ScheduleMedicineRecord medicine) {
      byId.put(medicine.id(), medicine);
      return medicine;
    }

    @Override
    public Optional<ScheduleMedicineRecord> findById(UUID medicineId) {
      return Optional.ofNullable(byId.get(medicineId));
    }

    @Override
    public List<ScheduleMedicineRecord> listByMember(
        UUID customerId, UUID memberId, boolean activeOnly) {
      return listActiveByCustomer(customerId).stream()
          .filter(m -> m.memberId().equals(memberId))
          .toList();
    }

    @Override
    public List<ScheduleMedicineRecord> listActiveByMember(UUID memberId) {
      return byId.values().stream()
          .filter(m -> m.memberId().equals(memberId) && m.active())
          .toList();
    }

    @Override
    public List<ScheduleMedicineRecord> listActiveByCustomer(UUID customerId) {
      return byId.values().stream()
          .filter(m -> m.customerId().equals(customerId) && m.active())
          .toList();
    }

    @Override
    public List<UUID> listCustomerIdsWithActiveMedicines() {
      return byId.values().stream()
          .filter(ScheduleMedicineRecord::active)
          .map(ScheduleMedicineRecord::customerId)
          .distinct()
          .toList();
    }

    @Override
    public int decrementUnitsInHand(UUID medicineId, Instant updatedAt) {
      ScheduleMedicineRecord m = byId.get(medicineId);
      if (m == null || m.unitsInHand() <= 0) {
        return 0;
      }
      byId.put(
          medicineId,
          new ScheduleMedicineRecord(
              m.id(),
              m.customerId(),
              m.memberId(),
              m.masterMedicineId(),
              m.medicineName(),
              m.strength(),
              m.dose(),
              m.form(),
              m.doseSlots(),
              m.foodInstruction(),
              m.durationType(),
              m.durationDays(),
              m.startedOnDate(),
              m.endedOnDate(),
              m.conditionName(),
              m.prescribedBy(),
              m.unitsInHand() - 1,
              m.refillRemindAtUnits(),
              m.notes(),
              m.active(),
              m.createdAt(),
              updatedAt));
      return 1;
    }

    @Override
    public Optional<Integer> decrementUnitsBy(UUID medicineId, int amount, Instant updatedAt) {
      return Optional.empty();
    }

    @Override
    public List<ScheduleMedicineRecord> listActiveWithSupplyTracking() {
      return List.of();
    }

    @Override
    public List<ScheduleMedicineRecord> listRefillAlertsNeedingPush(LocalDate today) {
      return List.of();
    }

    @Override
    public void markRefillAlertPushedOn(UUID medicineId, LocalDate pushedOn, Instant updatedAt) {}

    @Override
    public int softArchiveByMember(UUID memberId, LocalDate endedOn, Instant updatedAt) {
      return 0;
    }
  }

  private static final class FakeDoseLogs implements DoseLogStore {
    private final Map<String, DoseLogRecord> byKey = new ConcurrentHashMap<>();
    private final Map<UUID, DoseLogRecord> byId = new ConcurrentHashMap<>();

    List<DoseLogRecord> all() {
      return new ArrayList<>(byId.values());
    }

    private static String key(UUID medicineId, LocalDate date, String slot) {
      return medicineId + "|" + date + "|" + slot;
    }

    @Override
    public DoseLogRecord upsertUpcoming(DoseLogRecord draft) {
      String k = key(draft.medicineId(), draft.doseDate(), draft.slot());
      DoseLogRecord existing = byKey.get(k);
      if (existing != null) {
        if ("UPCOMING".equals(existing.status())) {
          DoseLogRecord updated =
              new DoseLogRecord(
                  existing.id(),
                  existing.medicineId(),
                  existing.customerId(),
                  existing.memberId(),
                  existing.doseDate(),
                  existing.slot(),
                  draft.reminderTime(),
                  existing.status(),
                  existing.takenAt(),
                  existing.locked(),
                  existing.createdAt(),
                  draft.updatedAt());
          byKey.put(k, updated);
          byId.put(updated.id(), updated);
          return updated;
        }
        return existing;
      }
      byKey.put(k, draft);
      byId.put(draft.id(), draft);
      return draft;
    }

    @Override
    public Optional<DoseLogRecord> findByMedicineDateSlot(
        UUID medicineId, LocalDate doseDate, String slot) {
      return Optional.ofNullable(byKey.get(key(medicineId, doseDate, slot)));
    }

    @Override
    public Optional<DoseLogRecord> findById(UUID doseLogId) {
      return Optional.ofNullable(byId.get(doseLogId));
    }

    @Override
    public List<DoseLogRecord> listByMemberAndDate(UUID memberId, LocalDate doseDate) {
      return byId.values().stream()
          .filter(l -> l.memberId().equals(memberId) && l.doseDate().equals(doseDate))
          .sorted((a, b) -> a.reminderTime().compareTo(b.reminderTime()))
          .toList();
    }

    @Override
    public List<DoseLogRecord> listUpcomingByMemberUntil(UUID memberId, Instant until) {
      return byId.values().stream()
          .filter(l -> l.memberId().equals(memberId) && "UPCOMING".equals(l.status()))
          .filter(
              l ->
                  !ReminderRecalcService.toUtcInstant(l.doseDate(), l.reminderTime())
                      .isAfter(until))
          .toList();
    }

    @Override
    public DoseLogRecord updateStatus(
        UUID doseLogId, String status, Instant takenAt, boolean locked, Instant updatedAt) {
      DoseLogRecord cur = byId.get(doseLogId);
      DoseLogRecord updated =
          new DoseLogRecord(
              cur.id(),
              cur.medicineId(),
              cur.customerId(),
              cur.memberId(),
              cur.doseDate(),
              cur.slot(),
              cur.reminderTime(),
              status,
              takenAt,
              locked,
              cur.createdAt(),
              updatedAt);
      byId.put(doseLogId, updated);
      byKey.put(key(updated.medicineId(), updated.doseDate(), updated.slot()), updated);
      return updated;
    }

    @Override
    public int markMissedBefore(Instant cutoff, Instant updatedAt) {
      int n = 0;
      for (DoseLogRecord l : List.copyOf(byId.values())) {
        Instant scheduled = ReminderRecalcService.toUtcInstant(l.doseDate(), l.reminderTime());
        if ("UPCOMING".equals(l.status()) && scheduled.isBefore(cutoff)) {
          updateStatus(l.id(), "MISSED", null, true, updatedAt);
          n++;
        }
      }
      return n;
    }

    @Override
    public TodayCounts countsForMemberOn(UUID memberId, LocalDate doseDate) {
      List<DoseLogRecord> rows = listByMemberAndDate(memberId, doseDate);
      return count(rows);
    }

    @Override
    public TodayCounts countsForMedicineOn(UUID medicineId, LocalDate doseDate) {
      List<DoseLogRecord> rows =
          byId.values().stream()
              .filter(l -> l.medicineId().equals(medicineId) && l.doseDate().equals(doseDate))
              .toList();
      return count(rows);
    }

    @Override
    public List<DailyCounts> dailyCountsForMember(
        UUID memberId, LocalDate fromInclusive, LocalDate toInclusive) {
      return daily(
          byId.values().stream()
              .filter(
                  l ->
                      l.memberId().equals(memberId)
                          && !l.doseDate().isBefore(fromInclusive)
                          && !l.doseDate().isAfter(toInclusive))
              .toList());
    }

    @Override
    public List<DailyCounts> dailyCountsForMedicine(
        UUID medicineId, LocalDate fromInclusive, LocalDate toInclusive) {
      return daily(
          byId.values().stream()
              .filter(
                  l -> {
                    if (!l.medicineId().equals(medicineId)) {
                      return false;
                    }
                    if (fromInclusive != null && l.doseDate().isBefore(fromInclusive)) {
                      return false;
                    }
                    if (toInclusive != null && l.doseDate().isAfter(toInclusive)) {
                      return false;
                    }
                    return true;
                  })
              .toList());
    }

    @Override
    public TodayCounts countsForMemberBetween(
        UUID memberId, LocalDate fromInclusive, LocalDate toInclusive) {
      return count(
          byId.values().stream()
              .filter(
                  l ->
                      l.memberId().equals(memberId)
                          && !l.doseDate().isBefore(fromInclusive)
                          && !l.doseDate().isAfter(toInclusive))
              .toList());
    }

    @Override
    public TodayCounts countsForMedicineBetween(
        UUID medicineId, LocalDate fromInclusive, LocalDate toInclusive) {
      return count(
          byId.values().stream()
              .filter(
                  l -> {
                    if (!l.medicineId().equals(medicineId)) {
                      return false;
                    }
                    if (fromInclusive != null && l.doseDate().isBefore(fromInclusive)) {
                      return false;
                    }
                    if (toInclusive != null && l.doseDate().isAfter(toInclusive)) {
                      return false;
                    }
                    return true;
                  })
              .toList());
    }

    private static List<DailyCounts> daily(List<DoseLogRecord> rows) {
      Map<LocalDate, List<DoseLogRecord>> byDate = new java.util.TreeMap<>();
      for (DoseLogRecord r : rows) {
        byDate.computeIfAbsent(r.doseDate(), d -> new ArrayList<>()).add(r);
      }
      List<DailyCounts> out = new ArrayList<>();
      for (Map.Entry<LocalDate, List<DoseLogRecord>> e : byDate.entrySet()) {
        TodayCounts c = count(e.getValue());
        out.add(
            new DailyCounts(
                e.getKey(), c.total(), c.taken(), c.skipped(), c.missed(), c.upcoming()));
      }
      return out;
    }

    private static TodayCounts count(List<DoseLogRecord> rows) {
      int taken = 0, skipped = 0, missed = 0, upcoming = 0;
      for (DoseLogRecord r : rows) {
        switch (r.status()) {
          case "TAKEN" -> taken++;
          case "SKIPPED" -> skipped++;
          case "MISSED" -> missed++;
          default -> upcoming++;
        }
      }
      return new TodayCounts(rows.size(), taken, skipped, missed, upcoming);
    }
  }

  private static final class FakeReminders implements ReminderScheduleStore {
    private final Map<UUID, ReminderRecord> byDoseLog = new ConcurrentHashMap<>();
    private final Map<UUID, ReminderRecord> byId = new ConcurrentHashMap<>();

    List<ReminderRecord> all() {
      return new ArrayList<>(byId.values());
    }

    @Override
    public ReminderRecord upsertScheduled(ReminderRecord draft) {
      ReminderRecord existing = byDoseLog.get(draft.doseLogId());
      if (existing != null) {
        ReminderRecord updated =
            new ReminderRecord(
                existing.id(),
                existing.medicineId(),
                existing.customerId(),
                existing.doseLogId(),
                draft.scheduledAt(),
                draft.channel(),
                "SCHEDULED",
                null,
                null,
                null,
                null,
                existing.createdAt());
        byDoseLog.put(draft.doseLogId(), updated);
        byId.put(updated.id(), updated);
        return updated;
      }
      byDoseLog.put(draft.doseLogId(), draft);
      byId.put(draft.id(), draft);
      return draft;
    }

    @Override
    public Optional<ReminderRecord> findByDoseLogId(UUID doseLogId) {
      return Optional.ofNullable(byDoseLog.get(doseLogId));
    }

    @Override
    public int cancelFutureScheduled(UUID medicineId, Instant now) {
      int n = 0;
      for (ReminderRecord r : List.copyOf(byId.values())) {
        if (r.medicineId().equals(medicineId)
            && "SCHEDULED".equals(r.status())
            && !r.scheduledAt().isBefore(now)) {
          ReminderRecord cancelled =
              new ReminderRecord(
                  r.id(),
                  r.medicineId(),
                  r.customerId(),
                  r.doseLogId(),
                  r.scheduledAt(),
                  r.channel(),
                  "CANCELLED",
                  r.notificationId(),
                  r.sentAt(),
                  r.deliveredAt(),
                  r.openedAt(),
                  r.createdAt());
          byId.put(r.id(), cancelled);
          byDoseLog.put(r.doseLogId(), cancelled);
          n++;
        }
      }
      return n;
    }

    @Override
    public int cancelFutureNotInSlots(UUID medicineId, List<String> keepSlots, Instant now) {
      // FakeDoseLogs slot lookup not wired; cancel nothing for slot-diff in unit tests
      return 0;
    }

    @Override
    public List<ReminderRecord> findDueScheduled(Instant now, int limit) {
      return byId.values().stream()
          .filter(r -> "SCHEDULED".equals(r.status()) && !r.scheduledAt().isAfter(now))
          .limit(limit)
          .toList();
    }

    @Override
    public void markSent(UUID reminderId, Instant sentAt, String notificationId) {
      ReminderRecord r = byId.get(reminderId);
      ReminderRecord updated =
          new ReminderRecord(
              r.id(),
              r.medicineId(),
              r.customerId(),
              r.doseLogId(),
              r.scheduledAt(),
              r.channel(),
              "SENT",
              notificationId,
              sentAt,
              r.deliveredAt(),
              r.openedAt(),
              r.createdAt());
      byId.put(reminderId, updated);
      byDoseLog.put(r.doseLogId(), updated);
    }
  }

  private static final class FakeMembers implements CareCircleMemberStore {
    private final Map<UUID, MemberRecord> byId = new ConcurrentHashMap<>();

    @Override
    public List<MemberRecord> listByCustomer(UUID customerId) {
      return new ArrayList<>(byId.values());
    }

    @Override
    public int countByCustomer(UUID customerId) {
      return byId.size();
    }

    @Override
    public Optional<MemberRecord> findById(UUID memberId) {
      return Optional.ofNullable(byId.get(memberId));
    }

    @Override
    public Optional<MemberRecord> findSelf(UUID customerId) {
      return byId.values().stream().filter(MemberRecord::self).findFirst();
    }

    @Override
    public MemberRecord insert(MemberRecord member) {
      byId.put(member.id(), member);
      return member;
    }

    @Override
    public MemberRecord update(MemberRecord member) {
      byId.put(member.id(), member);
      return member;
    }

    @Override
    public void softDelete(UUID memberId, Instant deletedAt) {}
  }
}
