package com.nammamedmate.medicine_schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore.MemberRecord;
import com.nammamedmate.medicine_schedule.application.port.out.NotificationDispatchPort;
import com.nammamedmate.medicine_schedule.application.port.out.RefillAlertQueryPort;
import com.nammamedmate.medicine_schedule.application.port.out.RefillLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.RefillLogStore.RefillLogRecord;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleMedicineStore.ScheduleMedicineRecord;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleShareTokenStore;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleShareTokenStore.ScheduleShareTokenRecord;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefillAlertServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-24T07:00:00Z"), ZoneOffset.UTC);

  private FakeMedicines medicines;
  private FakeMembers members;
  private CareCircleService careCircle;
  private RefillAlertQueryPort alertQuery;
  private FakeRefillLogs refillLogs;
  private FakeShareTokens shareTokens;
  private RecordingNotifications notifications;
  private RefillAlertService service;
  private UUID customerId;
  private MedmatePrincipal customer;
  private MemberRecord self;

  @BeforeEach
  void setUp() {
    medicines = new FakeMedicines();
    members = new FakeMembers();
    alertQuery = mock(RefillAlertQueryPort.class);
    refillLogs = new FakeRefillLogs();
    shareTokens = new FakeShareTokens();
    notifications = new RecordingNotifications();
    careCircle = mock(CareCircleService.class);
    customerId = Ids.newId();
    customer = new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    Instant now = CLOCK.instant();
    self =
        new MemberRecord(
            Ids.newId(),
            customerId,
            "Priya Sharma",
            30,
            "SELF",
            "👤",
            "#6B7280",
            true,
            now,
            now,
            null);
    members.byId.put(self.id(), self);
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
    service =
        new RefillAlertService(
            medicines,
            members,
            careCircle,
            alertQuery,
            refillLogs,
            shareTokens,
            notifications,
            CLOCK);
  }

  @Test
  void ac_listAlertsShowsMedicineInAlertState() {
    UUID medId = Ids.newId();
    when(alertQuery.refillAlerts(self.id()))
        .thenReturn(
            List.of(
                new RefillAlertQueryPort.RefillAlert(
                    medId,
                    "Metformin 500mg",
                    "500mg",
                    "TABLET",
                    8,
                    10,
                    2,
                    4,
                    null,
                    false,
                    "WARNING")));
    Map<String, Object> data = service.listAlerts(customer, null);
    assertThat(data.get("refill_alerts_count")).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> alerts = (List<Map<String, Object>>) data.get("alerts");
    assertThat(alerts.getFirst()).containsEntry("approx_days_left", 4);
    assertThat(alerts.getFirst()).containsEntry("medicine_id", medId);
  }

  @Test
  void ac_refillAddsUnitsAndClearsAlert() {
    ScheduleMedicineRecord med = insertMedicine(8, 10, Ids.newId(), twoSlots());
    Map<String, Object> data = service.recordRefill(customer, med.id(), 60, "2026-07-24");
    assertThat(data)
        .containsEntry("previous_units", 8)
        .containsEntry("new_units_in_hand", 68)
        .containsEntry("refill_alert_cleared", true)
        .containsEntry("approx_days_left", 34);
    assertThat(medicines.findById(med.id()).orElseThrow().unitsInHand()).isEqualTo(68);
    assertThat(refillLogs.logs).hasSize(1);
    assertThat(refillLogs.logs.getFirst().unitsAdded()).isEqualTo(60);
  }

  @Test
  void ac_invalidUnitsRejected() {
    ScheduleMedicineRecord med = insertMedicine(8, 10, null, twoSlots());
    assertThatThrownBy(() -> service.recordRefill(customer, med.id(), 0, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_UNITS");
    assertThatThrownBy(() -> service.recordRefill(customer, med.id(), null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_UNITS");
  }

  @Test
  void ac_orderOnlineIncludesMasterId() {
    UUID master = Ids.newId();
    ScheduleMedicineRecord med = insertMedicine(8, 10, master, twoSlots());
    Map<String, Object> data = service.orderOnline(customer, med.id());
    assertThat(data.get("redirect_url").toString()).contains("master_id=" + master);
    assertThat(data.get("web_redirect_url").toString()).contains("master_id=" + master);
    assertThat(data).containsEntry("master_medicine_id", master);
  }

  @Test
  void ac_shareLinkValidThirtyDaysAndPublicGet() {
    insertMedicine(30, 10, null, twoSlots());
    Map<String, Object> created = service.createShareLink(customer, null);
    assertThat(created.get("token").toString()).hasSize(32);
    Instant expires = (Instant) created.get("expires_at");
    assertThat(expires).isEqualTo(CLOCK.instant().plusSeconds(30L * 24 * 3600));
    Map<String, Object> shared = service.viewSharedSchedule(created.get("token").toString());
    assertThat(shared).containsEntry("member_name", "Priya Sharma");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> meds = (List<Map<String, Object>>) shared.get("medicines");
    assertThat(meds).hasSize(1);
    assertThat(meds.getFirst()).doesNotContainKey("units_in_hand");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> slots = (List<Map<String, Object>>) meds.getFirst().get("dose_slots");
    assertThat(slots.getFirst()).containsEntry("time", "08:00 AM");
  }

  @Test
  void ac_expiredShareReturns410() {
    Instant expiredAt = CLOCK.instant().minusSeconds(60);
    String token = "expiredToken123456789012345678";
    shareTokens.insert(
        new ScheduleShareTokenRecord(
            Ids.newId(), token, customerId, self.id(), expiredAt, CLOCK.instant()));
    assertThatThrownBy(() -> service.viewSharedSchedule(token))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("SHARE_LINK_EXPIRED");
              assertThat(ae.httpStatus()).isEqualTo(410);
            });
  }

  @Test
  void ac_thresholdZeroExcludedFromAlerts() {
    when(alertQuery.refillAlerts(self.id())).thenReturn(List.of());
    Map<String, Object> data = service.listAlerts(customer, self.id());
    assertThat(data.get("refill_alerts_count")).isEqualTo(0);
    verify(alertQuery).refillAlerts(self.id());
  }

  @Test
  void ac_nightlyDecrementTwoSlotsFiveToThree() {
    ScheduleMedicineRecord med = insertMedicine(5, 10, null, twoSlots());
    int n = service.runNightlySupplyDecrement();
    assertThat(n).isEqualTo(1);
    assertThat(medicines.findById(med.id()).orElseThrow().unitsInHand()).isEqualTo(3);
    assertThat(refillLogs.logs.getFirst().unitsAdded()).isEqualTo(-2);
    assertThat(service.runNightlySupplyDecrement()).isZero();
  }

  @Test
  void dispatchDailyRefillAlerts_oncePerDay() {
    ScheduleMedicineRecord med = insertMedicine(5, 10, null, twoSlots());
    medicines.needingPush.add(med);
    assertThat(service.dispatchDailyRefillAlerts()).isEqualTo(1);
    assertThat(notifications.refillCalls.get()).isEqualTo(1);
    assertThat(medicines.pushedOn.get(med.id())).isEqualTo(LocalDate.of(2026, 7, 24));
  }

  @Test
  void accessDeniedAndNotFoundBranches() {
    UUID other = Ids.newId();
    MemberRecord foreign =
        new MemberRecord(
            Ids.newId(),
            other,
            "X",
            1,
            "CHILD",
            "👤",
            "#6B7280",
            false,
            CLOCK.instant(),
            CLOCK.instant(),
            null);
    members.byId.put(foreign.id(), foreign);
    assertThatThrownBy(() -> service.listAlerts(customer, foreign.id()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEMBER_ACCESS_DENIED");
    assertThatThrownBy(() -> service.listAlerts(customer, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEMBER_NOT_FOUND");
    assertThatThrownBy(() -> service.recordRefill(customer, Ids.newId(), 1, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");
    ScheduleMedicineRecord foreignMed =
        new ScheduleMedicineRecord(
            Ids.newId(),
            other,
            foreign.id(),
            null,
            "X",
            null,
            "1",
            "TABLET",
            twoSlots(),
            "ANY",
            "ONGOING",
            null,
            LocalDate.of(2026, 7, 1),
            null,
            null,
            null,
            8,
            10,
            null,
            true,
            CLOCK.instant(),
            CLOCK.instant());
    medicines.insert(foreignMed);
    assertThatThrownBy(() -> service.recordRefill(customer, foreignMed.id(), 1, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_ACCESS_DENIED");
    assertThatThrownBy(() -> service.viewSharedSchedule("missing"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SHARE_LINK_NOT_FOUND");
    assertThatThrownBy(() -> service.viewSharedSchedule("  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SHARE_LINK_NOT_FOUND");
    assertThatThrownBy(
            () ->
                service.recordRefill(
                    customer, insertMedicine(1, 0, null, twoSlots()).id(), 1, "bad"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.listAlerts(null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void nightlySkipsZeroSlotsAndMissing() {
    insertMedicine(5, 10, null, List.of());
    medicines.tracking.add(
        new ScheduleMedicineRecord(
            Ids.newId(),
            customerId,
            self.id(),
            null,
            "Gone",
            null,
            "1",
            "TABLET",
            twoSlots(),
            "ANY",
            "ONGOING",
            null,
            LocalDate.of(2026, 7, 1),
            null,
            null,
            null,
            5,
            10,
            null,
            true,
            CLOCK.instant(),
            CLOCK.instant()));
    assertThat(service.runNightlySupplyDecrement()).isZero();
  }

  @Test
  void orderOnlineWithoutMasterAndDefaultRefillDate() {
    ScheduleMedicineRecord med = insertMedicine(2, 0, null, twoSlots());
    Map<String, Object> data = service.orderOnline(customer, med.id());
    assertThat(data.get("redirect_url").toString()).doesNotContain("master_id=");
    Map<String, Object> refill = service.recordRefill(customer, med.id(), 5, null);
    assertThat(refill)
        .containsEntry("refill_date", "2026-07-24")
        .containsEntry("refill_alert_cleared", true);
  }

  @Test
  void refillDoesNotClearWhenStillBelowThreshold() {
    ScheduleMedicineRecord med = insertMedicine(8, 10, null, twoSlots());
    Map<String, Object> data = service.recordRefill(customer, med.id(), 1, "  ");
    assertThat(data)
        .containsEntry("new_units_in_hand", 9)
        .containsEntry("refill_alert_cleared", false);
  }

  @Test
  void approxDaysNullWhenNoSlotsAndShareMemberMissing() {
    ScheduleMedicineRecord med = insertMedicine(8, 10, null, List.of());
    Map<String, Object> data = service.recordRefill(customer, med.id(), 60, null);
    assertThat(data)
        .containsEntry("approx_days_left", null)
        .containsEntry("refill_alert_cleared", true);

    String token = "orphanMemberToken12345678901234";
    shareTokens.insert(
        new ScheduleShareTokenRecord(
            Ids.newId(),
            token,
            customerId,
            Ids.newId(),
            CLOCK.instant().plusSeconds(3600),
            CLOCK.instant()));
    assertThatThrownBy(() -> service.viewSharedSchedule(token))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SHARE_LINK_NOT_FOUND");
    assertThatThrownBy(() -> service.viewSharedSchedule(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SHARE_LINK_NOT_FOUND");
  }

  @Test
  void nonCustomerRejected() {
    MedmatePrincipal pharmacy =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.listAlerts(pharmacy, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  private ScheduleMedicineRecord insertMedicine(
      int units, int refillAt, UUID masterId, List<DoseSlot> slots) {
    ScheduleMedicineRecord med =
        new ScheduleMedicineRecord(
            Ids.newId(),
            customerId,
            self.id(),
            masterId,
            "Metformin 500mg",
            "500mg",
            "1 tablet",
            "TABLET",
            slots,
            "AFTER",
            "ONGOING",
            null,
            LocalDate.of(2026, 7, 1),
            null,
            "Type 2 Diabetes",
            "Dr. Anil Sharma",
            units,
            refillAt,
            null,
            true,
            CLOCK.instant(),
            CLOCK.instant());
    medicines.insert(med);
    return med;
  }

  private static List<DoseSlot> twoSlots() {
    return List.of(new DoseSlot("MORNING", "08:00"), new DoseSlot("NIGHT", "21:00"));
  }

  private static final class RecordingNotifications implements NotificationDispatchPort {
    final AtomicInteger refillCalls = new AtomicInteger();

    @Override
    public void notifyDoseReminderDue(
        UUID customerId, UUID reminderId, UUID doseLogId, UUID medicineId) {}

    @Override
    public void notifyRefillAlert(
        UUID customerId, UUID medicineId, int unitsInHand, int refillRemindAtUnits) {
      refillCalls.incrementAndGet();
    }
  }

  private static final class FakeRefillLogs implements RefillLogStore {
    final List<RefillLogRecord> logs = new ArrayList<>();

    @Override
    public void insert(RefillLogRecord log) {
      logs.add(log);
    }

    @Override
    public boolean existsNegativeOnDate(UUID medicineId, LocalDate refillDate) {
      return logs.stream()
          .anyMatch(
              l ->
                  l.medicineId().equals(medicineId)
                      && l.refillDate().equals(refillDate)
                      && l.unitsAdded() < 0);
    }
  }

  private static final class FakeShareTokens implements ScheduleShareTokenStore {
    private final Map<String, ScheduleShareTokenRecord> byToken = new ConcurrentHashMap<>();

    @Override
    public ScheduleShareTokenRecord insert(ScheduleShareTokenRecord token) {
      byToken.put(token.token(), token);
      return token;
    }

    @Override
    public Optional<ScheduleShareTokenRecord> findByToken(String token) {
      return Optional.ofNullable(byToken.get(token));
    }
  }

  private static final class FakeMembers implements CareCircleMemberStore {
    final Map<UUID, MemberRecord> byId = new ConcurrentHashMap<>();

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
    public void softDelete(UUID memberId, Instant deletedAt) {}
  }

  private static final class FakeMedicines implements ScheduleMedicineStore {
    private final Map<UUID, ScheduleMedicineRecord> byId = new ConcurrentHashMap<>();
    final List<ScheduleMedicineRecord> tracking = new ArrayList<>();
    final List<ScheduleMedicineRecord> needingPush = new ArrayList<>();
    final Map<UUID, LocalDate> pushedOn = new ConcurrentHashMap<>();

    @Override
    public ScheduleMedicineRecord insert(ScheduleMedicineRecord medicine) {
      byId.put(medicine.id(), medicine);
      if (medicine.active() && medicine.unitsInHand() > 0 && medicine.refillRemindAtUnits() > 0) {
        tracking.add(medicine);
      }
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
      return listActiveByMember(memberId);
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
      ScheduleMedicineRecord m = byId.get(medicineId);
      if (m == null) {
        return Optional.empty();
      }
      int next = Math.max(0, m.unitsInHand() - amount);
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
              next,
              m.refillRemindAtUnits(),
              m.notes(),
              m.active(),
              m.createdAt(),
              updatedAt));
      return Optional.of(next);
    }

    @Override
    public List<ScheduleMedicineRecord> listActiveWithSupplyTracking() {
      return List.copyOf(tracking);
    }

    @Override
    public List<ScheduleMedicineRecord> listRefillAlertsNeedingPush(LocalDate today) {
      return List.copyOf(needingPush);
    }

    @Override
    public void markRefillAlertPushedOn(
        UUID medicineId, LocalDate pushedOnDate, Instant updatedAt) {
      pushedOn.put(medicineId, pushedOnDate);
    }

    @Override
    public int softArchiveByMember(UUID memberId, LocalDate endedOn, Instant updatedAt) {
      return 0;
    }
  }
}
