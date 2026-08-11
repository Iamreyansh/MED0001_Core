package com.nammamedmate.medicine_schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore.MemberRecord;
import com.nammamedmate.medicine_schedule.application.port.out.CustomerNamePort;
import com.nammamedmate.medicine_schedule.application.port.out.MemberCascadePort;
import com.nammamedmate.medicine_schedule.application.port.out.MemberStatsPort;
import com.nammamedmate.medicine_schedule.application.port.out.RefillAlertQueryPort;
import com.nammamedmate.medicine_schedule.application.port.out.TodayAdherencePort;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CareCircleServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T07:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private FakeStore store;
  private CustomerNamePort names;
  private MemberCascadePort cascade;
  private MemberStatsPort stats;
  private TodayAdherencePort adherence;
  private RefillAlertQueryPort refills;
  private CareCircleService service;
  private UUID customerId;
  private MedmatePrincipal customer;

  @BeforeEach
  void setUp() {
    store = new FakeStore();
    names = mock(CustomerNamePort.class);
    cascade = mock(MemberCascadePort.class);
    stats = mock(MemberStatsPort.class);
    adherence = mock(TodayAdherencePort.class);
    refills = mock(RefillAlertQueryPort.class);
    when(names.nameFor(any())).thenReturn("Priya Sharma");
    when(stats.statsForMember(any()))
        .thenReturn(new MemberStatsPort.MemberListStats(0, 0, 0, null, 0));
    when(adherence.todayForMember(any()))
        .thenReturn(new TodayAdherencePort.TodayAdherence(0, 0, 0, 0, 0, null));
    when(refills.refillAlerts(any())).thenReturn(List.of());
    when(refills.medicines(any())).thenReturn(List.of());
    when(refills.thisWeekAdherencePct(any())).thenReturn(null);
    when(cascade.cascadeOnDelete(any())).thenReturn(new MemberCascadePort.CascadeResult(0, 0));
    service = new CareCircleService(store, names, cascade, stats, adherence, refills, CLOCK);
    customerId = Ids.newId();
    customer = new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  }

  @Test
  void ensureSelf_createsSelfWhenMissing() {
    MemberRecord self = service.ensureSelf(customerId);

    assertThat(self.self()).isTrue();
    assertThat(self.relationship()).isEqualTo("SELF");
    assertThat(self.name()).isEqualTo("Priya Sharma");
    assertThat(store.findSelf(customerId)).contains(self);
  }

  @Test
  void ensureSelf_returnsExistingWithoutDuplicate() {
    MemberRecord first = service.ensureSelf(customerId);
    MemberRecord second = service.ensureSelf(customerId);
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(store.countByCustomer(customerId)).isEqualTo(1);
  }

  @Test
  void ensureSelf_blankNameFallsBackToCustomer() {
    when(names.nameFor(customerId)).thenReturn("  ");
    assertThat(service.ensureSelf(customerId).name()).isEqualTo("Customer");
  }

  @Test
  void ensureSelf_nullNameFallsBackToCustomer() {
    when(names.nameFor(customerId)).thenReturn(null);
    assertThat(service.ensureSelf(customerId).name()).isEqualTo("Customer");
  }

  @Test
  void list_includesTodayAdherencePctAndEnsuresSelf() {
    when(stats.statsForMember(any()))
        .thenReturn(new MemberStatsPort.MemberListStats(0, 5, 4, 80.0, 0));

    Map<String, Object> data = service.list(customer);

    assertThat(data.get("total_members")).isEqualTo(1);
    assertThat(data.get("can_add_more")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> members = (List<Map<String, Object>>) data.get("members");
    assertThat(members).hasSize(1);
    assertThat(members.getFirst()).containsEntry("today_adherence_pct", 80.0);
    assertThat(members.getFirst()).containsKey("today_adherence_pct");
  }

  @Test
  void create_limitReached() {
    service.ensureSelf(customerId);
    for (int i = 0; i < 9; i++) {
      service.create(
          customer, new CareCircleService.CreateCommand("M" + i, 10, "CHILD", null, null));
    }
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CareCircleService.CreateCommand("Overflow", 5, "OTHER", null, null)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CARE_CIRCLE_LIMIT_REACHED");
  }

  @Test
  void create_invalidAvatarColor() {
    service.ensureSelf(customerId);
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CareCircleService.CreateCommand(
                        "Rajesh", 68, "PARENT", null, "not_a_color")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AVATAR_COLOR");
  }

  @Test
  void create_invalidAge() {
    assertThatThrownBy(
            () ->
                service.create(
                    customer, new CareCircleService.CreateCommand("X", 121, "CHILD", null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AGE");
  }

  @Test
  void create_happyPath() {
    Map<String, Object> created =
        service.create(
            customer, new CareCircleService.CreateCommand("Rajesh", 68, "PARENT", "👴", "#10B981"));
    assertThat(created).containsEntry("name", "Rajesh").containsEntry("is_self", false);
    assertThat(created.get("member_id")).isInstanceOf(UUID.class);
  }

  @Test
  void delete_self_cannotDeleteSelf() {
    MemberRecord self = service.ensureSelf(customerId);
    assertThatThrownBy(() -> service.delete(customer, self.id()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_DELETE_SELF");
  }

  @Test
  void delete_callsCascadePort() {
    service.ensureSelf(customerId);
    Map<String, Object> created =
        service.create(
            customer, new CareCircleService.CreateCommand("Dad", 70, "PARENT", null, null));
    UUID memberId = (UUID) created.get("member_id");
    when(cascade.cascadeOnDelete(memberId)).thenReturn(new MemberCascadePort.CascadeResult(5, 28));

    Map<String, Object> deleted = service.delete(customer, memberId);

    verify(cascade).cascadeOnDelete(memberId);
    assertThat(deleted)
        .containsEntry("medicines_archived", 5)
        .containsEntry("reminders_cancelled", 28);
    assertThat(store.findById(memberId)).isEmpty();
  }

  @Test
  void update_crossCustomer_accessDenied() {
    UUID other = Ids.newId();
    MemberRecord foreign =
        store.insert(
            new MemberRecord(
                Ids.newId(), other, "Other", 40, "SELF", "👤", "#6B7280", true, NOW, NOW, null));
    assertThatThrownBy(
            () ->
                service.update(
                    customer,
                    foreign.id(),
                    new CareCircleService.UpdateCommand("X", null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEMBER_ACCESS_DENIED");
  }

  @Test
  void update_notFound() {
    assertThatThrownBy(
            () ->
                service.update(
                    customer,
                    Ids.newId(),
                    new CareCircleService.UpdateCommand("X", 1, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEMBER_NOT_FOUND");
  }

  @Test
  void summary_structure() {
    service.ensureSelf(customerId);
    Map<String, Object> created =
        service.create(
            customer, new CareCircleService.CreateCommand("Dad", 68, "PARENT", null, null));
    UUID memberId = (UUID) created.get("member_id");
    when(adherence.todayForMember(memberId))
        .thenReturn(new TodayAdherencePort.TodayAdherence(8, 5, 1, 0, 2, 62.5));
    when(refills.thisWeekAdherencePct(memberId)).thenReturn(78.5);
    when(refills.refillAlerts(memberId))
        .thenReturn(
            List.of(
                new RefillAlertQueryPort.RefillAlert(
                    Ids.newId(),
                    "Amlodipine 5mg",
                    "5mg",
                    "TABLET",
                    8,
                    10,
                    2,
                    4,
                    null,
                    false,
                    "WARNING"),
                new RefillAlertQueryPort.RefillAlert(
                    Ids.newId(),
                    "No slots",
                    null,
                    "TABLET",
                    2,
                    5,
                    0,
                    null,
                    null,
                    false,
                    "WARNING")));
    UUID medId = Ids.newId();
    when(refills.medicines(memberId))
        .thenReturn(
            List.of(
                new RefillAlertQueryPort.MedicineSummary(
                    medId,
                    "Amlodipine 5mg",
                    "1 tablet",
                    "TABLET",
                    List.of(new RefillAlertQueryPort.DoseSlot("MORNING", "08:00")),
                    true)));

    Map<String, Object> data = service.summary(customer, memberId);

    assertThat(data)
        .containsKeys("member", "today", "this_week_adherence_pct", "refill_alerts", "medicines");
    @SuppressWarnings("unchecked")
    Map<String, Object> today = (Map<String, Object>) data.get("today");
    assertThat(today).containsEntry("adherence_pct", 62.5).containsEntry("taken", 5);
    assertThat(data.get("this_week_adherence_pct")).isEqualTo(78.5);
    assertThat((List<?>) data.get("refill_alerts")).hasSize(2);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> alertRows = (List<Map<String, Object>>) data.get("refill_alerts");
    assertThat(alertRows.get(1)).containsEntry("approx_days_left", 0);
    assertThat((List<?>) data.get("medicines")).hasSize(1);

    // cover MedicineSummary null doseSlots → List.of()
    assertThat(
            new RefillAlertQueryPort.MedicineSummary(Ids.newId(), "X", "1", "TABLET", null, true)
                .doseSlots())
        .isEmpty();
  }

  @Test
  void list_canAddMoreFalseAtLimit() {
    service.ensureSelf(customerId);
    for (int i = 0; i < 9; i++) {
      service.create(
          customer, new CareCircleService.CreateCommand("M" + i, 10, "CHILD", null, null));
    }
    Map<String, Object> data = service.list(customer);
    assertThat(data.get("total_members")).isEqualTo(10);
    assertThat(data.get("can_add_more")).isEqualTo(false);
  }

  @Test
  void coverageFill_edgeValidations() {
    assertThatThrownBy(() -> service.list(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(admin))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    assertThatThrownBy(
            () ->
                service.create(
                    customer, new CareCircleService.CreateCommand(null, 10, "CHILD", null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    customer, new CareCircleService.CreateCommand("X", null, "CHILD", null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AGE");

    assertThatThrownBy(
            () ->
                service.create(
                    customer, new CareCircleService.CreateCommand("X", -1, "CHILD", null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AGE");

    Map<String, Object> created =
        service.create(
            customer, new CareCircleService.CreateCommand("Kid", 8, "CHILD", "  ", null));
    assertThat(created.get("avatar_emoji")).isEqualTo(CareCircleService.DEFAULT_AVATAR_EMOJI);

    UUID id = (UUID) created.get("member_id");
    Map<String, Object> patched =
        service.update(
            customer, id, new CareCircleService.UpdateCommand(null, null, null, null, "#AABBCC"));
    assertThat(patched.get("name")).isEqualTo("Kid");

    assertThatThrownBy(
            () ->
                service.update(
                    customer,
                    id,
                    new CareCircleService.UpdateCommand(null, null, null, null, "  ")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AVATAR_COLOR");
  }

  @Test
  void create_validationBranches() {
    assertThatThrownBy(() -> service.create(customer, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    customer, new CareCircleService.CreateCommand(" ", 10, "CHILD", null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CareCircleService.CreateCommand("n".repeat(101), 10, "CHILD", null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    customer, new CareCircleService.CreateCommand("X", 10, "SELF", null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    customer,
                    new CareCircleService.CreateCommand("X", 10, "CHILD", "e".repeat(11), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void update_happyAndSelfRelationshipBlocked() {
    MemberRecord self = service.ensureSelf(customerId);
    assertThatThrownBy(
            () ->
                service.update(
                    customer,
                    self.id(),
                    new CareCircleService.UpdateCommand(null, null, "SPOUSE", null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    Map<String, Object> created =
        service.create(
            customer, new CareCircleService.CreateCommand("Kid", 8, "CHILD", null, null));
    UUID id = (UUID) created.get("member_id");
    Map<String, Object> updated =
        service.update(
            customer,
            id,
            new CareCircleService.UpdateCommand("Kiddo", 9, "CHILD", "🧒", "#ABCDEF"));
    assertThat(updated).containsEntry("name", "Kiddo").containsEntry("age", 9);

    Map<String, Object> nameOnly =
        service.update(
            customer, id, new CareCircleService.UpdateCommand("Kiddo2", null, null, null, null));
    assertThat(nameOnly).containsEntry("name", "Kiddo2");

    assertThatThrownBy(() -> service.update(customer, id, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.update(
                    customer,
                    id,
                    new CareCircleService.UpdateCommand(null, null, "NOPE", null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.update(
                    customer,
                    id,
                    new CareCircleService.UpdateCommand(null, null, null, null, "bad")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AVATAR_COLOR");
  }

  private static final class FakeStore implements CareCircleMemberStore {
    private final Map<UUID, MemberRecord> byId = new ConcurrentHashMap<>();

    @Override
    public List<MemberRecord> listByCustomer(UUID customerId) {
      List<MemberRecord> out = new ArrayList<>();
      for (MemberRecord m : byId.values()) {
        if (m.customerId().equals(customerId) && m.deletedAt() == null) {
          out.add(m);
        }
      }
      out.sort(
          (a, b) -> {
            if (a.self() != b.self()) {
              return a.self() ? -1 : 1;
            }
            return a.createdAt().compareTo(b.createdAt());
          });
      return out;
    }

    @Override
    public int countByCustomer(UUID customerId) {
      return listByCustomer(customerId).size();
    }

    @Override
    public Optional<MemberRecord> findById(UUID memberId) {
      MemberRecord m = byId.get(memberId);
      if (m == null || m.deletedAt() != null) {
        return Optional.empty();
      }
      return Optional.of(m);
    }

    @Override
    public Optional<MemberRecord> findSelf(UUID customerId) {
      return listByCustomer(customerId).stream().filter(MemberRecord::self).findFirst();
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
      MemberRecord m = byId.get(memberId);
      if (m != null) {
        byId.put(
            memberId,
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
}
