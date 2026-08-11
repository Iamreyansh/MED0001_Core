package com.nammamedmate.medicine_schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore.MemberRecord;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.DailyCounts;
import com.nammamedmate.medicine_schedule.application.port.out.DoseLogStore.TodayCounts;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdherenceServiceTest {

  /** Friday 2026-07-24 12:30 IST = 07:00 UTC. */
  private static final Instant NOW = Instant.parse("2026-07-24T07:00:00Z");

  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final LocalDate TODAY = LocalDate.of(2026, 7, 24);

  private DoseLogStore doseLogs;
  private CareCircleMemberStore members;
  private CareCircleService careCircle;
  private ScheduleMedicineStore medicines;
  private AdherenceService service;
  private UUID customerId;
  private UUID memberId;
  private MedmatePrincipal customer;
  private MemberRecord self;

  @BeforeEach
  void setUp() {
    doseLogs = mock(DoseLogStore.class);
    members = mock(CareCircleMemberStore.class);
    careCircle = mock(CareCircleService.class);
    medicines = mock(ScheduleMedicineStore.class);
    service = new AdherenceService(doseLogs, members, careCircle, medicines, CLOCK);
    customerId = Ids.newId();
    memberId = Ids.newId();
    customer = new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    self =
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
    when(members.findSelf(customerId)).thenReturn(Optional.of(self));
    when(members.findById(memberId)).thenReturn(Optional.of(self));
  }

  @Test
  void ac_calendarPartial75() {
    when(doseLogs.dailyCountsForMember(eq(memberId), any(), any()))
        .thenReturn(
            List.of(
                new DailyCounts(LocalDate.of(2026, 7, 2), 4, 3, 0, 1, 0),
                new DailyCounts(LocalDate.of(2026, 7, 10), 0, 0, 0, 0, 0)));

    @SuppressWarnings("unchecked")
    Map<String, Object> data = service.calendar(customer, memberId, "2026-07");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> days = (List<Map<String, Object>>) data.get("days");
    Map<String, Object> jul2 =
        days.stream().filter(d -> "2026-07-02".equals(d.get("date"))).findFirst().orElseThrow();
    assertThat(jul2).containsEntry("status", "PARTIAL").containsEntry("pct", 75.0);
  }

  @Test
  void ac_calendarNoDoses() {
    when(doseLogs.dailyCountsForMember(eq(memberId), any(), any())).thenReturn(List.of());
    @SuppressWarnings("unchecked")
    Map<String, Object> data = service.calendar(customer, memberId, "2026-07");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> days = (List<Map<String, Object>>) data.get("days");
    Map<String, Object> jul10 =
        days.stream().filter(d -> "2026-07-10".equals(d.get("date"))).findFirst().orElseThrow();
    assertThat(jul10).containsEntry("status", "NO_DOSES").containsEntry("pct", null);
  }

  @Test
  void ac_streakFiveAfterPriorPartial() {
    // Partial then 5 perfect days ending today → current_streak_days = 5
    List<DailyCounts> days = new ArrayList<>();
    days.add(new DailyCounts(TODAY.minusDays(5), 4, 2, 0, 2, 0)); // PARTIAL breaks prior
    for (int i = 4; i >= 0; i--) {
      days.add(new DailyCounts(TODAY.minusDays(i), 4, 4, 0, 0, 0));
    }
    when(doseLogs.dailyCountsForMember(eq(memberId), any(), any())).thenReturn(days);
    when(doseLogs.countsForMemberBetween(eq(memberId), any(), any()))
        .thenReturn(new TodayCounts(20, 18, 0, 2, 0));

    Map<String, Object> data = service.summary(customer, memberId);
    assertThat(data.get("current_streak_days")).isEqualTo(5);
  }

  @Test
  void ac_medicineAllTimePct() {
    UUID medicineId = Ids.newId();
    when(medicines.findById(medicineId))
        .thenReturn(Optional.of(medicine(medicineId, customerId, memberId)));
    when(doseLogs.countsForMedicineBetween(eq(medicineId), any(), any()))
        .thenAnswer(
            inv -> {
              LocalDate from = inv.getArgument(1);
              if (from == null) {
                return new TodayCounts(368, 310, 24, 34, 0);
              }
              return new TodayCounts(28, 26, 0, 2, 0);
            });
    when(doseLogs.dailyCountsForMedicine(eq(medicineId), isNull(), eq(TODAY)))
        .thenReturn(
            List.of(
                new DailyCounts(LocalDate.of(2026, 7, 5), 4, 0, 0, 4, 0),
                new DailyCounts(LocalDate.of(2026, 7, 12), 4, 0, 0, 4, 0)));

    Map<String, Object> data = service.medicineAdherence(customer, medicineId);
    assertThat(data.get("all_time_pct")).isEqualTo(84.24); // 310/368*100 rounded
    assertThat(data.get("total_doses_taken")).isEqualTo(310);
    assertThat(data.get("total_doses_scheduled")).isEqualTo(368);
    @SuppressWarnings("unchecked")
    List<String> missed = (List<String>) data.get("missed_days_list");
    assertThat(missed).containsExactly("2026-07-12", "2026-07-05");
  }

  @Test
  void ac_chartWeeks4() {
    when(doseLogs.dailyCountsForMember(eq(memberId), any(), any()))
        .thenReturn(
            List.of(
                new DailyCounts(LocalDate.of(2026, 7, 20), 4, 4, 0, 0, 0),
                new DailyCounts(LocalDate.of(2026, 7, 21), 4, 4, 0, 0, 0),
                new DailyCounts(LocalDate.of(2026, 7, 22), 4, 3, 0, 1, 0)));

    Map<String, Object> data = service.chart(customer, memberId, 4);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> weeks = (List<Map<String, Object>>) data.get("weeks");
    assertThat(weeks).hasSize(4);
    assertThat(weeks.getFirst())
        .containsKeys("week_start", "week_end", "week_label", "adherence_pct");
  }

  @Test
  void ac_chartHighBand() {
    // Week Mon Jul 20 – Sun Jul 26 with ≥85%
    when(doseLogs.dailyCountsForMember(eq(memberId), any(), any()))
        .thenReturn(
            List.of(
                new DailyCounts(LocalDate.of(2026, 7, 20), 10, 9, 0, 1, 0),
                new DailyCounts(LocalDate.of(2026, 7, 21), 10, 9, 0, 1, 0),
                new DailyCounts(LocalDate.of(2026, 7, 22), 10, 8, 0, 2, 0)));

    Map<String, Object> data = service.chart(customer, memberId, 1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> weeks = (List<Map<String, Object>>) data.get("weeks");
    Map<String, Object> week = weeks.getFirst();
    Double pct = (Double) week.get("adherence_pct");
    assertThat(pct).isGreaterThanOrEqualTo(85.0);
    assertThat(week.get("status")).isEqualTo("HIGH");
  }

  @Test
  void ac_memberAccessDenied() {
    UUID other = Ids.newId();
    when(members.findById(other))
        .thenReturn(
            Optional.of(
                new MemberRecord(
                    other,
                    Ids.newId(),
                    "Other",
                    40,
                    "CHILD",
                    "👤",
                    "#6B7280",
                    false,
                    NOW,
                    NOW,
                    null)));
    assertThatThrownBy(() -> service.summary(customer, other))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEMBER_ACCESS_DENIED");
  }

  @Test
  void ac_monthlyReverseChrono() {
    when(doseLogs.dailyCountsForMember(eq(memberId), any(), any()))
        .thenReturn(
            List.of(
                new DailyCounts(LocalDate.of(2026, 5, 1), 4, 3, 0, 1, 0),
                new DailyCounts(LocalDate.of(2026, 6, 1), 4, 4, 0, 0, 0),
                new DailyCounts(LocalDate.of(2026, 7, 1), 4, 4, 0, 0, 0)));
    when(doseLogs.countsForMemberBetween(eq(memberId), any(), any()))
        .thenReturn(new TodayCounts(12, 11, 0, 1, 0));

    Map<String, Object> data = service.summary(customer, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> monthly = (List<Map<String, Object>>) data.get("monthly_adherence");
    assertThat(monthly)
        .extracting(m -> m.get("month"))
        .containsExactly("2026-07", "2026-06", "2026-05");
  }

  @Test
  void medicineAccessDeniedAndNotFound() {
    UUID medicineId = Ids.newId();
    when(medicines.findById(medicineId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.medicineAdherence(customer, medicineId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");

    when(medicines.findById(medicineId))
        .thenReturn(Optional.of(medicine(medicineId, Ids.newId(), Ids.newId())));
    assertThatThrownBy(() -> service.medicineAdherence(customer, medicineId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_ACCESS_DENIED");
  }

  @Test
  void validationBranches() {
    assertThatThrownBy(() -> service.calendar(customer, memberId, "07-2026"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.chart(customer, memberId, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.chart(customer, memberId, 53))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.summary(null, memberId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void memberNotFoundAndEnsureSelf() {
    when(members.findSelf(customerId)).thenReturn(Optional.empty());
    when(careCircle.ensureSelf(customerId)).thenReturn(self);
    when(doseLogs.dailyCountsForMember(eq(memberId), any(), any())).thenReturn(List.of());
    when(doseLogs.countsForMemberBetween(eq(memberId), any(), any()))
        .thenReturn(new TodayCounts(0, 0, 0, 0, 0));
    assertThat(service.summary(customer, null).get("current_streak_days")).isEqualTo(0);

    when(members.findById(Ids.newId())).thenReturn(Optional.empty());
    UUID missing = Ids.newId();
    when(members.findById(missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.summary(customer, missing))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEMBER_NOT_FOUND");
  }

  @Test
  void streakHelpers_partialBreaksAndNoDosesSkipped() {
    List<DailyCounts> days =
        List.of(
            new DailyCounts(TODAY.minusDays(3), 4, 4, 0, 0, 0),
            new DailyCounts(TODAY.minusDays(1), 4, 2, 0, 2, 0), // PARTIAL
            new DailyCounts(TODAY, 4, 4, 0, 0, 0));
    assertThat(AdherenceService.currentStreak(days, TODAY)).isEqualTo(1);
    assertThat(AdherenceService.longestStreak(days)).isEqualTo(1);

    List<DailyCounts> withGap =
        List.of(
            new DailyCounts(TODAY.minusDays(2), 4, 4, 0, 0, 0),
            new DailyCounts(TODAY, 4, 4, 0, 0, 0));
    assertThat(AdherenceService.currentStreak(withGap, TODAY)).isEqualTo(2);
    assertThat(AdherenceService.longestStreak(withGap)).isEqualTo(2);
    assertThat(AdherenceService.currentStreak(List.of(), TODAY)).isZero();
  }

  @Test
  void calendarDefaultMonthAndChartDefaultWeeks() {
    when(doseLogs.dailyCountsForMember(eq(memberId), any(), any())).thenReturn(List.of());
    assertThat(service.calendar(customer, memberId, null).get("month")).isEqualTo("2026-07");
    assertThat(service.calendar(customer, memberId, "  ").get("month")).isEqualTo("2026-07");

    Map<String, Object> chart = service.chart(customer, memberId, null);
    @SuppressWarnings("unchecked")
    List<?> weeks = (List<?>) chart.get("weeks");
    assertThat(weeks).hasSize(12);
  }

  @Test
  void nonCustomerUnauthorized() {
    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.summary(admin, memberId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void medicineMissedDaysSkipsNonMissed() {
    UUID medicineId = Ids.newId();
    when(medicines.findById(medicineId))
        .thenReturn(Optional.of(medicine(medicineId, customerId, memberId)));
    when(doseLogs.countsForMedicineBetween(eq(medicineId), any(), any()))
        .thenReturn(new TodayCounts(8, 4, 0, 4, 0));
    when(doseLogs.dailyCountsForMedicine(eq(medicineId), isNull(), eq(TODAY)))
        .thenReturn(
            List.of(
                new DailyCounts(LocalDate.of(2026, 7, 5), 4, 4, 0, 0, 0),
                new DailyCounts(LocalDate.of(2026, 7, 12), 4, 0, 0, 4, 0)));
    @SuppressWarnings("unchecked")
    List<String> missed =
        (List<String>) service.medicineAdherence(customer, medicineId).get("missed_days_list");
    assertThat(missed).containsExactly("2026-07-12");
  }

  @Test
  void streakIgnoresFutureAndZeroTotalDays() {
    List<DailyCounts> days =
        List.of(
            new DailyCounts(TODAY.minusDays(1), 0, 0, 0, 0, 0),
            new DailyCounts(TODAY, 4, 4, 0, 0, 0),
            new DailyCounts(TODAY.plusDays(1), 4, 4, 0, 0, 0));
    assertThat(AdherenceService.currentStreak(days, TODAY)).isEqualTo(1);
    assertThat(AdherenceService.longestStreak(days)).isEqualTo(2);
  }

  @Test
  void monthlySkipsZeroTotal() {
    when(doseLogs.dailyCountsForMember(eq(memberId), any(), any()))
        .thenReturn(
            List.of(
                new DailyCounts(LocalDate.of(2026, 7, 1), 0, 0, 0, 0, 0),
                new DailyCounts(LocalDate.of(2026, 7, 2), 4, 4, 0, 0, 0)));
    when(doseLogs.countsForMemberBetween(eq(memberId), any(), any()))
        .thenReturn(new TodayCounts(4, 4, 0, 0, 0));
    Map<String, Object> data = service.summary(customer, memberId);
    assertThat(data.get("total_days_tracked")).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> monthly = (List<Map<String, Object>>) data.get("monthly_adherence");
    assertThat(monthly).hasSize(1);
  }

  @Test
  void weekLabelAcrossMonths() {
    when(doseLogs.dailyCountsForMember(eq(memberId), any(), any())).thenReturn(List.of());
    Map<String, Object> data = service.chart(customer, memberId, 4);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> weeks = (List<Map<String, Object>>) data.get("weeks");
    // 4th week is Mon Jun 29 – Sun Jul 5 (cross-month)
    assertThat(weeks.get(3).get("week_label").toString()).contains("Jun").contains("Jul");
  }

  private static ScheduleMedicineRecord medicine(UUID id, UUID customerId, UUID memberId) {
    return new ScheduleMedicineRecord(
        id,
        customerId,
        memberId,
        null,
        "Metformin 500mg",
        "500mg",
        "1 tablet",
        "TABLET",
        List.of(new DoseSlot("MORNING", "08:00")),
        "AFTER",
        "ONGOING",
        null,
        LocalDate.of(2026, 1, 1),
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
