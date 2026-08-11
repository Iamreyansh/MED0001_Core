package com.nammamedmate.medicine_schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.ScheduleMedicineService.CreateCommand;
import com.nammamedmate.medicine_schedule.application.ScheduleMedicineService.DoseSlotInput;
import com.nammamedmate.medicine_schedule.application.ScheduleMedicineService.UpdateCommand;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore.MemberRecord;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.TodayCounts;
import com.nammamedmate.medicine_schedule.application.port.out.ReminderRecalcPort;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore.ScheduleMedicineRecord;
import com.nammamedmate.medicine_schedule.domain.DoseSlot;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduleMedicineServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T07:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private FakeMedicineStore store;
  private FakeMemberStore members;
  private CareCircleService careCircle;
  private ReminderRecalcPort reminders;
  private DoseLogStore doseLogs;
  private ScheduleMedicineService service;
  private UUID customerId;
  private MedmatePrincipal customer;

  @BeforeEach
  void setUp() {
    store = new FakeMedicineStore();
    members = new FakeMemberStore();
    careCircle = mock(CareCircleService.class);
    reminders = mock(ReminderRecalcPort.class);
    doseLogs = mock(DoseLogStore.class);
    when(doseLogs.countsForMedicineOn(any(), any())).thenReturn(new TodayCounts(0, 0, 0, 0, 0));
    when(doseLogs.countsForMedicineBetween(any(), any(), any()))
        .thenReturn(new TodayCounts(0, 0, 0, 0, 0));
    when(reminders.recalculate(any()))
        .thenAnswer(
            inv -> store.findById(inv.getArgument(0)).map(m -> 7 * m.doseSlots().size()).orElse(0));
    when(reminders.cancelFuture(any()))
        .thenAnswer(
            inv -> store.findById(inv.getArgument(0)).map(m -> 7 * m.doseSlots().size()).orElse(0));
    service = new ScheduleMedicineService(store, members, careCircle, reminders, doseLogs, CLOCK);
    customerId = Ids.newId();
    customer = new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  }

  @Test
  void ac_missingDurationDays() {
    MemberRecord self = seedSelf();
    when(careCircle.ensureSelf(customerId)).thenReturn(self);

    assertThatThrownBy(() -> service.create(customer, createCmd(null, "DAYS", null, twoSlots())))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_DURATION_DAYS");
  }

  @Test
  void ac_tooManyDoseSlots() {
    MemberRecord self = seedSelf();
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
    List<DoseSlotInput> seven = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      seven.add(new DoseSlotInput("CUSTOM", String.format("%02d:00", i + 8)));
    }

    assertThatThrownBy(() -> service.create(customer, createCmd(null, "ONGOING", null, seven)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TOO_MANY_DOSE_SLOTS");
  }

  @Test
  void ac_memberAccessDenied() {
    UUID otherCustomer = Ids.newId();
    MemberRecord otherMember =
        new MemberRecord(
            Ids.newId(), otherCustomer, "Other", 40, "SELF", "👤", "#6B7280", true, NOW, NOW, null);
    members.insert(otherMember);

    assertThatThrownBy(
            () ->
                service.create(customer, createCmd(otherMember.id(), "ONGOING", null, twoSlots())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEMBER_ACCESS_DENIED");
  }

  @Test
  void ac_remindersScheduledEqualsSevenTimesSlots_andEnsureSelf() {
    MemberRecord self = seedSelf();
    when(careCircle.ensureSelf(customerId)).thenReturn(self);

    Map<String, Object> data =
        service.create(customer, createCmd(null, "ONGOING", null, twoSlots()));

    assertThat(data.get("reminders_scheduled")).isEqualTo(14);
    verify(reminders).recalculate(any());
    verify(careCircle).ensureSelf(customerId);
  }

  @Test
  void ac_deleteSoftArchivesAndCancelsReminders() {
    MemberRecord self = seedSelf();
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
    UUID medicineId =
        (UUID)
            service
                .create(customer, createCmd(null, "ONGOING", null, twoSlots()))
                .get("medicine_id");

    Map<String, Object> deleted = service.delete(customer, medicineId);

    assertThat(deleted.get("is_active")).isEqualTo(false);
    assertThat(deleted.get("ended_on_date")).isEqualTo("2026-07-24");
    assertThat(deleted.get("reminders_cancelled")).isEqualTo(14);
    assertThat(store.findById(medicineId))
        .get()
        .extracting(ScheduleMedicineRecord::active)
        .isEqualTo(false);
    verify(reminders).cancelFuture(medicineId);
  }

  @Test
  void ac_listDefaultsToSelfMember() {
    MemberRecord self = seedSelf();
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
    service.create(customer, createCmd(null, "ONGOING", null, twoSlots()));

    Map<String, Object> data = service.list(customer, null, null);

    @SuppressWarnings("unchecked")
    Map<String, Object> member = (Map<String, Object>) data.get("member");
    assertThat(member.get("member_id")).isEqualTo(self.id());
    assertThat(member.get("relationship")).isEqualTo("SELF");
    assertThat(data.get("total_medicines")).isEqualTo(1);
  }

  @Test
  void ac_patchDoseSlotsRecalculatesReminders() {
    MemberRecord self = seedSelf();
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
    UUID medicineId =
        (UUID)
            service
                .create(customer, createCmd(null, "ONGOING", null, twoSlots()))
                .get("medicine_id");

    Map<String, Object> updated =
        service.update(
            customer,
            medicineId,
            new UpdateCommand(
                null,
                null,
                false,
                null,
                null,
                null,
                List.of(
                    new DoseSlotInput("MORNING", "07:00"),
                    new DoseSlotInput("AFTERNOON", "13:00"),
                    new DoseSlotInput("NIGHT", "21:00")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

    assertThat(updated.get("reminders_rescheduled")).isEqualTo(true);
    assertThat(updated.get("updated_fields")).asList().contains("dose_slots");
    verify(reminders).cancelFuture(medicineId);
    verify(reminders, times(2)).recalculate(medicineId);
  }

  @Test
  void ac_approxDaysLeftForTwoDosePerDay() {
    MemberRecord self = seedSelf();
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
    UUID medicineId =
        (UUID)
            service
                .create(
                    customer,
                    new CreateCommand(
                        null,
                        "Metformin 500mg",
                        null,
                        "500mg",
                        "1 tablet",
                        "TABLET",
                        twoSlots(),
                        "AFTER",
                        "ONGOING",
                        null,
                        "2026-01-15",
                        "Type 2 Diabetes",
                        "Dr. Anil Sharma",
                        30,
                        10,
                        "Take with breakfast"))
                .get("medicine_id");

    Map<String, Object> detail = service.get(customer, medicineId);

    assertThat(detail.get("approx_days_left")).isEqualTo(15);
  }

  @Test
  void create_daysComputesEndedOnDate() {
    MemberRecord self = seedSelf();
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
    UUID medicineId =
        (UUID) service.create(customer, createCmd(null, "DAYS", 10, twoSlots())).get("medicine_id");
    assertThat(store.findById(medicineId))
        .get()
        .extracting(ScheduleMedicineRecord::endedOnDate)
        .isEqualTo(LocalDate.parse("2026-08-03"));
  }

  @Test
  void create_invalidReminderTime() {
    MemberRecord self = seedSelf();
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    createCmd(
                        null, "ONGOING", null, List.of(new DoseSlotInput("MORNING", "25:00")))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REMINDER_TIME");
  }

  @Test
  void create_memberNotFound() {
    assertThatThrownBy(
            () -> service.create(customer, createCmd(Ids.newId(), "ONGOING", null, twoSlots())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEMBER_NOT_FOUND");
  }

  @Test
  void get_medicineAccessDenied() {
    UUID other = Ids.newId();
    MemberRecord otherSelf =
        new MemberRecord(Ids.newId(), other, "X", 1, "SELF", "👤", "#6B7280", true, NOW, NOW, null);
    members.insert(otherSelf);
    ScheduleMedicineRecord med =
        store.insert(
            new ScheduleMedicineRecord(
                Ids.newId(),
                other,
                otherSelf.id(),
                null,
                "X",
                null,
                "1",
                "TABLET",
                List.of(new DoseSlot("MORNING", "08:00")),
                "ANY",
                "ONGOING",
                null,
                LocalDate.parse("2026-07-24"),
                null,
                null,
                null,
                0,
                0,
                null,
                true,
                NOW,
                NOW));

    assertThatThrownBy(() -> service.get(customer, med.id()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_ACCESS_DENIED");
  }

  @Test
  void get_notFound() {
    assertThatThrownBy(() -> service.get(customer, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");
  }

  @Test
  void patch_withoutSlotChangeDoesNotReschedule() {
    MemberRecord self = seedSelf();
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
    UUID medicineId =
        (UUID)
            service
                .create(customer, createCmd(null, "ONGOING", null, twoSlots()))
                .get("medicine_id");

    Map<String, Object> updated =
        service.update(
            customer,
            medicineId,
            new UpdateCommand(
                "New name",
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

    assertThat(updated.get("reminders_rescheduled")).isEqualTo(false);
    verify(reminders, never()).cancelFuture(medicineId);
  }

  @Test
  void list_ensuresSelfWhenMissing() {
    MemberRecord self =
        new MemberRecord(
            Ids.newId(), customerId, "Priya", 30, "SELF", "👤", "#6B7280", true, NOW, NOW, null);
    when(careCircle.ensureSelf(customerId)).thenReturn(self);

    Map<String, Object> data = service.list(customer, null, true);

    verify(careCircle).ensureSelf(customerId);
    assertThat(data.get("total_medicines")).isEqualTo(0);
  }

  @Test
  void approxDaysLeft_nullWhenNoSlots() {
    MemberRecord self = seedSelf();
    ScheduleMedicineRecord med =
        store.insert(
            new ScheduleMedicineRecord(
                Ids.newId(),
                customerId,
                self.id(),
                null,
                "X",
                null,
                "1",
                "TABLET",
                List.of(),
                "ANY",
                "ONGOING",
                null,
                LocalDate.parse("2026-07-24"),
                null,
                null,
                null,
                10,
                0,
                null,
                true,
                NOW,
                NOW));

    assertThat(service.get(customer, med.id()).get("approx_days_left")).isNull();
  }

  @Test
  void unauthorized() {
    MedmatePrincipal pharmacy =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(pharmacy, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void create_nullBody() {
    assertThatThrownBy(() -> service.create(customer, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  private MemberRecord seedSelf() {
    MemberRecord self =
        new MemberRecord(
            Ids.newId(),
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
    return self;
  }

  private static List<DoseSlotInput> twoSlots() {
    return List.of(new DoseSlotInput("MORNING", "08:00"), new DoseSlotInput("NIGHT", "21:00"));
  }

  private static CreateCommand createCmd(
      UUID memberId, String durationType, Integer durationDays, List<DoseSlotInput> slots) {
    return new CreateCommand(
        memberId,
        "Metformin 500mg",
        null,
        "500mg",
        "1 tablet",
        "TABLET",
        slots,
        "AFTER",
        durationType,
        durationDays,
        "2026-07-24",
        null,
        null,
        0,
        0,
        null);
  }

  private static final class FakeMedicineStore implements ScheduleMedicineStore {
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
      return byId.values().stream()
          .filter(m -> m.customerId().equals(customerId) && m.memberId().equals(memberId))
          .filter(m -> !activeOnly || m.active())
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
      int count = 0;
      for (ScheduleMedicineRecord m : List.copyOf(byId.values())) {
        if (m.memberId().equals(memberId) && m.active()) {
          byId.put(
              m.id(),
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
                  endedOn,
                  m.conditionName(),
                  m.prescribedBy(),
                  m.unitsInHand(),
                  m.refillRemindAtUnits(),
                  m.notes(),
                  false,
                  m.createdAt(),
                  updatedAt));
          count++;
        }
      }
      return count;
    }
  }

  private static final class FakeMemberStore implements CareCircleMemberStore {
    private final Map<UUID, MemberRecord> byId = new ConcurrentHashMap<>();

    @Override
    public List<MemberRecord> listByCustomer(UUID customerId) {
      return byId.values().stream().filter(m -> m.customerId().equals(customerId)).toList();
    }

    @Override
    public int countByCustomer(UUID customerId) {
      return listByCustomer(customerId).size();
    }

    @Override
    public Optional<MemberRecord> findById(UUID memberId) {
      return Optional.ofNullable(byId.get(memberId));
    }

    @Override
    public Optional<MemberRecord> findSelf(UUID customerId) {
      return byId.values().stream()
          .filter(m -> m.customerId().equals(customerId) && m.self())
          .findFirst();
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
    public void softDelete(UUID memberId, Instant deletedAt) {
      byId.computeIfPresent(
          memberId,
          (id, m) ->
              new MemberRecord(
                  m.id(),
                  m.customerId(),
                  m.name(),
                  m.age(),
                  m.relationship(),
                  m.avatarEmoji(),
                  m.avatarColor(),
                  m.self(),
                  m.createdAt(),
                  deletedAt,
                  deletedAt));
    }
  }
}
