package com.nammamedmate.medicine_schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

/** Extra branches for JaCoCo 100% on ScheduleMedicineService. */
class ScheduleMedicineServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T07:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private FakeStore store;
  private FakeMembers members;
  private CareCircleService careCircle;
  private ReminderRecalcPort reminders;
  private DoseLogStore doseLogs;
  private ScheduleMedicineService service;
  private UUID customerId;
  private MedmatePrincipal customer;
  private MemberRecord self;

  @BeforeEach
  void setUp() {
    store = new FakeStore();
    members = new FakeMembers();
    careCircle = mock(CareCircleService.class);
    reminders = mock(ReminderRecalcPort.class);
    doseLogs = mock(DoseLogStore.class);
    when(doseLogs.countsForMedicineOn(any(), any())).thenReturn(new TodayCounts(0, 0, 0, 0, 0));
    when(doseLogs.countsForMedicineBetween(any(), any(), any()))
        .thenReturn(new TodayCounts(0, 0, 0, 0, 0));
    when(reminders.recalculate(any())).thenReturn(7);
    when(reminders.cancelFuture(any())).thenReturn(7);
    service = new ScheduleMedicineService(store, members, careCircle, reminders, doseLogs, CLOCK);
    customerId = Ids.newId();
    customer = new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    self =
        new MemberRecord(
            Ids.newId(), customerId, "Priya", 30, "SELF", "👤", "#6B7280", true, NOW, NOW, null);
    members.insert(self);
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
  }

  @Test
  void create_invalidForm() {
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    cmd(
                        "PILL",
                        "AFTER",
                        "ONGOING",
                        null,
                        List.of(new DoseSlotInput("MORNING", "08:00")))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void create_validationBranches() {
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        null,
                        null,
                        null,
                        "1",
                        "TABLET",
                        List.of(new DoseSlotInput("MORNING", "08:00")),
                        "ANY",
                        "ONGOING",
                        null,
                        "2026-07-24",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        null,
                        null,
                        "TABLET",
                        List.of(new DoseSlotInput("MORNING", "08:00")),
                        "ANY",
                        "ONGOING",
                        null,
                        "2026-07-24",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        null,
                        "1",
                        "TABLET",
                        List.of(),
                        "ANY",
                        "ONGOING",
                        null,
                        "2026-07-24",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        null,
                        "1",
                        "TABLET",
                        null,
                        "ANY",
                        "ONGOING",
                        null,
                        "2026-07-24",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        null,
                        "1",
                        "TABLET",
                        java.util.Collections.singletonList(null),
                        "ANY",
                        "ONGOING",
                        null,
                        "2026-07-24",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        null,
                        "1",
                        "TABLET",
                        List.of(new DoseSlotInput("DAWN", "08:00")),
                        "ANY",
                        "ONGOING",
                        null,
                        "2026-07-24",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        null,
                        "1",
                        "TABLET",
                        List.of(new DoseSlotInput("MORNING", "08:00")),
                        "ANY",
                        "ONGOING",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        null,
                        "1",
                        "TABLET",
                        List.of(new DoseSlotInput("MORNING", "08:00")),
                        "ANY",
                        "ONGOING",
                        null,
                        "not-a-date",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "x".repeat(201),
                        null,
                        null,
                        "1",
                        "TABLET",
                        List.of(new DoseSlotInput("MORNING", "08:00")),
                        "ANY",
                        "ONGOING",
                        null,
                        "2026-07-24",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        null,
                        "x".repeat(101),
                        "TABLET",
                        List.of(new DoseSlotInput("MORNING", "08:00")),
                        "ANY",
                        "ONGOING",
                        null,
                        "2026-07-24",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        "x".repeat(51),
                        "1",
                        "TABLET",
                        List.of(new DoseSlotInput("MORNING", "08:00")),
                        "ANY",
                        "ONGOING",
                        null,
                        "2026-07-24",
                        null,
                        null,
                        -1,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        "  ",
                        "1",
                        "TABLET",
                        List.of(new DoseSlotInput("MORNING", "08:00")),
                        "ANY",
                        "ONGOING",
                        null,
                        "2026-07-24",
                        null,
                        null,
                        0,
                        -1,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void update_allFieldsAndDurationSwitch() {
    UUID medicineId =
        (UUID)
            service
                .create(
                    customer,
                    cmd(
                        "TABLET",
                        "AFTER",
                        "ONGOING",
                        null,
                        List.of(new DoseSlotInput("MORNING", "08:00"))))
                .get("medicine_id");

    Map<String, Object> updated =
        service.update(
            customer,
            medicineId,
            new UpdateCommand(
                "New Med",
                Ids.newId(),
                true,
                "250mg",
                "2 tablets",
                "CAPSULE",
                List.of(new DoseSlotInput("NIGHT", "21:00")),
                "BEFORE",
                "DAYS",
                5,
                "2026-07-20",
                "Flu",
                "Dr X",
                20,
                5,
                "note"));

    assertThat(updated.get("reminders_rescheduled")).isEqualTo(true);
    ScheduleMedicineRecord saved = store.findById(medicineId).orElseThrow();
    assertThat(saved.medicineName()).isEqualTo("New Med");
    assertThat(saved.durationType()).isEqualTo("DAYS");
    assertThat(saved.endedOnDate()).isEqualTo(LocalDate.parse("2026-07-25"));
    assertThat(saved.form()).isEqualTo("CAPSULE");

    service.update(
        customer,
        medicineId,
        new UpdateCommand(
            null, null, false, null, null, null, null, null, "ONGOING", null, null, null, null,
            null, null, null));
    assertThat(store.findById(medicineId).orElseThrow().durationDays()).isNull();
    assertThat(store.findById(medicineId).orElseThrow().endedOnDate()).isNull();
  }

  @Test
  void update_invalidFields() {
    UUID medicineId =
        (UUID)
            service
                .create(
                    customer,
                    cmd(
                        "TABLET",
                        "AFTER",
                        "ONGOING",
                        null,
                        List.of(new DoseSlotInput("MORNING", "08:00"))))
                .get("medicine_id");

    assertThatThrownBy(() -> service.update(customer, medicineId, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.update(
                    customer,
                    medicineId,
                    new UpdateCommand(
                        null, null, false, null, null, "PILL", null, null, null, null, null, null,
                        null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
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
                        null,
                        "SOMETIMES",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.update(
                    customer,
                    medicineId,
                    new UpdateCommand(
                        null, null, false, null, null, null, null, null, "WEEKLY", null, null, null,
                        null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.update(
                    customer,
                    medicineId,
                    new UpdateCommand(
                        null, null, false, null, null, null, null, null, "DAYS", null, null, null,
                        null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_DURATION_DAYS");
  }

  @Test
  void list_withExplicitMemberAndInactive() {
    service.create(
        customer,
        cmd("TABLET", "AFTER", "ONGOING", null, List.of(new DoseSlotInput("MORNING", "08:00"))));
    Map<String, Object> data = service.list(customer, self.id(), false);
    assertThat(data.get("total_medicines")).isEqualTo(1);
  }

  @Test
  void get_endedOnDatePresent() {
    UUID medicineId =
        (UUID)
            service
                .create(
                    customer,
                    cmd(
                        "TABLET",
                        "AFTER",
                        "DAYS",
                        3,
                        List.of(new DoseSlotInput("MORNING", "08:00"))))
                .get("medicine_id");
    Map<String, Object> detail = service.get(customer, medicineId);
    assertThat(detail.get("ended_on_date")).isEqualTo("2026-07-27");
  }

  @Test
  void listItem_endedOnDateNullAndPresent() {
    UUID id1 =
        (UUID)
            service
                .create(
                    customer,
                    cmd(
                        "TABLET",
                        "AFTER",
                        "ONGOING",
                        null,
                        List.of(new DoseSlotInput("MORNING", "08:00"))))
                .get("medicine_id");
    service.delete(customer, id1);
    Map<String, Object> data = service.list(customer, self.id(), false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> meds = (List<Map<String, Object>>) data.get("medicines");
    assertThat(meds.getFirst().get("ended_on_date")).isEqualTo("2026-07-24");
  }

  @Test
  void create_withMemberIdUsesOwnedMember() {
    Map<String, Object> data =
        service.create(
            customer,
            new CreateCommand(
                self.id(),
                "Med",
                null,
                null,
                "1",
                "TABLET",
                List.of(new DoseSlotInput("MORNING", "08:00")),
                "ANY",
                "ONGOING",
                null,
                "2026-07-24",
                "  ",
                "  ",
                null,
                null,
                "  "));
    assertThat(data.get("member_id")).isEqualTo(self.id());
  }

  @Test
  void update_sameSlotsDoesNotReschedule() {
    UUID medicineId =
        (UUID)
            service
                .create(
                    customer,
                    cmd(
                        "TABLET",
                        "AFTER",
                        "ONGOING",
                        null,
                        List.of(new DoseSlotInput("MORNING", "08:00"))))
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
                List.of(new DoseSlotInput("MORNING", "08:00")),
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
  }

  @Test
  void update_startedOnDateOnlyAndDurationDaysOnly() {
    UUID medicineId =
        (UUID)
            service
                .create(
                    customer,
                    cmd(
                        "TABLET",
                        "AFTER",
                        "DAYS",
                        10,
                        List.of(new DoseSlotInput("MORNING", "08:00"))))
                .get("medicine_id");

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
            null,
            null,
            null,
            null,
            "2026-07-20",
            null,
            null,
            null,
            null,
            null));
    assertThat(store.findById(medicineId).orElseThrow().startedOnDate())
        .isEqualTo(LocalDate.parse("2026-07-20"));
    assertThat(store.findById(medicineId).orElseThrow().endedOnDate())
        .isEqualTo(LocalDate.parse("2026-07-30"));

    service.update(
        customer,
        medicineId,
        new UpdateCommand(
            null, null, false, null, null, null, null, null, null, 3, null, null, null, null, null,
            null));
    assertThat(store.findById(medicineId).orElseThrow().durationDays()).isEqualTo(3);
  }

  @Test
  void blankFieldValidations() {
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "  ",
                        null,
                        null,
                        "1",
                        "TABLET",
                        List.of(new DoseSlotInput("MORNING", "08:00")),
                        "ANY",
                        "ONGOING",
                        null,
                        "2026-07-24",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        null,
                        "  ",
                        "TABLET",
                        List.of(new DoseSlotInput("MORNING", "08:00")),
                        "ANY",
                        "ONGOING",
                        null,
                        "2026-07-24",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        null,
                        "1",
                        "TABLET",
                        List.of(new DoseSlotInput("MORNING", "08:00")),
                        "ANY",
                        "ONGOING",
                        null,
                        "  ",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CreateCommand(
                        null,
                        "Med",
                        null,
                        null,
                        "1",
                        "TABLET",
                        List.of(new DoseSlotInput("MORNING", "08:00")),
                        "ANY",
                        "DAYS",
                        0,
                        "2026-07-24",
                        null,
                        null,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_DURATION_DAYS");

    assertThatThrownBy(() -> service.list(null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  private CreateCommand cmd(
      String form, String food, String duration, Integer days, List<DoseSlotInput> slots) {
    return new CreateCommand(
        null,
        "Med",
        null,
        null,
        "1",
        form,
        slots,
        food,
        duration,
        days,
        "2026-07-24",
        null,
        null,
        0,
        0,
        null);
  }

  private static final class FakeStore implements ScheduleMedicineStore {
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
      return List.of();
    }

    @Override
    public int decrementUnitsInHand(UUID medicineId, Instant updatedAt) {
      return 0;
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
      return member;
    }

    @Override
    public void softDelete(UUID memberId, Instant deletedAt) {}
  }
}
