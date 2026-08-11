package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.AcquisitionRow;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.CohortCell;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.GrowthTotals;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.Month1Retention;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.OrderTrendPoint;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.SpendRow;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrowthAnalyticsServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");
  private static final Instant COMPUTED = Instant.parse("2026-07-20T03:14:00Z");

  @Mock private PlatformGrowthStore store;

  private GrowthAnalyticsService service;
  private MedmatePrincipal ops;
  private MedmatePrincipal support;

  @BeforeEach
  void setUp() {
    service = new GrowthAnalyticsService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac001_growthReturnsMonth1RetentionWithCohortWeek() {
    when(store.liveGrowth(any(), any())).thenReturn(new GrowthTotals(4820, 812, 1986));
    when(store.month1Retention(any()))
        .thenReturn(Optional.of(new Month1Retention("2026-W24", bd("38.5"))));

    Map<String, Object> data = service.growth(ops, "30D", null, null);

    Map<String, Object> kpis = (Map<String, Object>) data.get("kpis");
    Map<String, Object> month1 = (Map<String, Object>) kpis.get("month1_retention_pct");
    assertThat((BigDecimal) month1.get("value")).isEqualByComparingTo("38.5");
    assertThat(month1.get("cohort_week")).isEqualTo("2026-W24");
    assertThat(month1).containsKey("wow_delta_pct");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac002_cohortWeek0Always100() {
    when(store.cohortMatrix(12))
        .thenReturn(
            List.of(
                cell("2026-W17", 284, 0, 284, "100.00"),
                cell("2026-W17", 284, 1, 137, "48.20"),
                cell("2026-W16", 318, 0, 318, "100.00"),
                cell("2026-W16", 318, 1, 163, "51.30")));
    when(store.cohortLastComputedAt()).thenReturn(Optional.of(COMPUTED));

    Map<String, Object> data = service.cohort(ops, null);
    List<Map<String, Object>> cohorts = (List<Map<String, Object>>) data.get("cohorts");
    for (Map<String, Object> row : cohorts) {
      List<BigDecimal> pcts = (List<BigDecimal>) row.get("retention_pcts");
      assertThat(pcts.getFirst()).isEqualByComparingTo("100.0");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac003_cohortNullForFutureElapsedWeeks() {
    // Current IST week of 2026-07-24 is 2026-W30; cohort W29 has only elapsed 0–1 complete.
    when(store.cohortMatrix(1))
        .thenReturn(
            List.of(
                cell("2026-W29", 100, 0, 100, "100.00"), cell("2026-W29", 100, 1, 50, "50.00")));
    when(store.cohortLastComputedAt()).thenReturn(Optional.of(COMPUTED));

    Map<String, Object> data = service.cohort(ops, 1);
    List<Map<String, Object>> cohorts = (List<Map<String, Object>>) data.get("cohorts");
    List<BigDecimal> pcts = (List<BigDecimal>) cohorts.getFirst().get("retention_pcts");
    assertThat(pcts.get(0)).isEqualByComparingTo("100.0");
    assertThat(pcts.get(1)).isEqualByComparingTo("50.0");
    assertThat(pcts.get(12)).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac004_acquisitionPctsSumTo100() {
    when(store.liveAcquisition(any(), any()))
        .thenReturn(
            List.of(
                new AcquisitionRow("ORGANIC", 412, 680, 842000),
                new AcquisitionRow("REFERRAL", 198, 312, 384000),
                new AcquisitionRow("AD", 152, 220, 278000),
                new AcquisitionRow("PARTNER", 50, 74, 96000)));
    when(store.campaignSpend(any(), any()))
        .thenReturn(
            List.of(
                new SpendRow("REFERRAL", bd("8316")),
                new SpendRow("AD", bd("27360")),
                new SpendRow("PARTNER", bd("4750"))));

    Map<String, Object> data = service.acquisition(ops, "30D", null, null);
    List<Map<String, Object>> sources = (List<Map<String, Object>>) data.get("sources");
    BigDecimal sum =
        sources.stream()
            .map(m -> (BigDecimal) m.get("pct"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo("100.0");
    assertThat(data.get("total_new_users")).isEqualTo(812L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac005_organicCacAlwaysZero() {
    when(store.liveAcquisition(any(), any()))
        .thenReturn(List.of(new AcquisitionRow("ORGANIC", 100, 150, 200000)));
    when(store.campaignSpend(any(), any())).thenReturn(List.of());

    Map<String, Object> data = service.acquisition(ops, "7D", null, null);
    Map<String, Object> organic = ((List<Map<String, Object>>) data.get("sources")).getFirst();
    assertThat(organic.get("source")).isEqualTo("ORGANIC");
    assertThat(organic.get("cac_rs")).isEqualTo(0L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac006_orderTrendWeeklyOnePointPerIsoWeek() {
    when(store.orderTrendWeekly(any(), any()))
        .thenReturn(
            List.of(
                new OrderTrendPoint(LocalDate.of(2026, 6, 29), 100, 20, 80),
                new OrderTrendPoint(LocalDate.of(2026, 7, 6), 120, 25, 95),
                new OrderTrendPoint(LocalDate.of(2026, 7, 13), 110, 22, 88),
                new OrderTrendPoint(LocalDate.of(2026, 7, 20), 90, 18, 72)));

    Map<String, Object> data = service.orderTrend(ops, "30D", "WEEKLY");
    assertThat(data.get("granularity")).isEqualTo("WEEKLY");
    List<Map<String, Object>> trend = (List<Map<String, Object>>) data.get("trend");
    assertThat(trend).hasSize(4);
    assertThat(trend.getFirst().get("date")).isEqualTo("2026-06-29");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac007_newPlusReturningEqualsTotal() {
    when(store.orderTrendDaily(any(), any()))
        .thenReturn(
            List.of(
                new OrderTrendPoint(LocalDate.of(2026, 7, 23), 118, 28, 90),
                new OrderTrendPoint(LocalDate.of(2026, 7, 24), 132, 31, 101)));

    Map<String, Object> data = service.orderTrend(ops, "7D", "DAILY");
    List<Map<String, Object>> trend = (List<Map<String, Object>>) data.get("trend");
    for (Map<String, Object> row : trend) {
      long total = ((Number) row.get("total_orders")).longValue();
      long neu = ((Number) row.get("new_customer_orders")).longValue();
      long ret = ((Number) row.get("returning_customer_orders")).longValue();
      assertThat(neu + ret).isEqualTo(total);
    }
  }

  @Test
  void ac008_cohortReadsPrecomputedNeverRefreshes() {
    when(store.cohortMatrix(12)).thenReturn(List.of(cell("2026-W20", 10, 0, 10, "100.00")));
    when(store.cohortLastComputedAt()).thenReturn(Optional.of(COMPUTED));

    Map<String, Object> data = service.cohort(ops, null);

    assertThat(data.get("last_computed_at")).isEqualTo(COMPUTED.toString());
    verify(store, never()).refreshCohortRetention(anyInt(), any());
    verify(store).cohortMatrix(12);
    verify(store).cohortLastComputedAt();
  }

  @Test
  void forbiddenAndValidationErrors() {
    assertThatThrownBy(() -> service.growth(support, "30D", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.growth(ops, "TODAY", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
    assertThatThrownBy(() -> service.cohort(ops, 27))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("COHORT_COUNT_TOO_LARGE");
    assertThatThrownBy(() -> service.orderTrend(ops, "7D", "MONTHLY"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_GRANULARITY");
  }

  private static CohortCell cell(String week, int size, int elapsed, int retained, String pct) {
    return new CohortCell(week, size, elapsed, retained, bd(pct), COMPUTED);
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}
