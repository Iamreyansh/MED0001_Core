package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.FinanceOverviewCachePort;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort.ChartGranularity;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort.ChartPoint;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort.KpiSnapshot;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort.PeriodTotals;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
class FinanceOverviewServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");

  @Mock private FinanceOverviewQueryPort store;
  @Mock private FinanceOverviewCachePort cache;

  private FinanceOverviewService service;
  private MedmatePrincipal finance;
  private MedmatePrincipal support;
  private final ObjectMapper om = new ObjectMapper();

  @BeforeEach
  void setUp() {
    service = new FinanceOverviewService(store, cache, om, Clock.fixed(NOW, ZoneOffset.UTC));
    finance =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  }

  @Test
  void ac001_kpiGmvTodayFromCapturedPayments() {
    when(cache.getKpiJson()).thenReturn(Optional.empty());
    when(store.kpi(any(), any()))
        .thenReturn(
            new KpiSnapshot(
                18_500_000L,
                1_480_000L,
                42_050_000L,
                7_240_000L,
                12,
                845_000L,
                2_845_000L,
                125_000_000L,
                273_600L));

    Map<String, Object> data = service.kpi(finance);

    assertThat(data.get("gmv_today")).isEqualTo(new BigDecimal("185000.00"));
    assertThat(data.get("as_of")).isEqualTo(NOW.toString());
    assertThat(data.get("refunds_pending")).isEqualTo(12L);
    verify(cache).putKpiJson(any());
  }

  @Test
  void ac008_kpiReturnsCachedPayloadWithAsOf() throws Exception {
    String cached =
        """
        {"as_of":"2026-07-24T15:59:30Z","gmv_today":100.00,"platform_revenue_today":8.00,\
        "pharmacy_payout_due":0.00,"rider_payout_due":0.00,"refunds_pending":0,\
        "refunds_pending_value":0.00,"cod_in_hand":0.00,"active_wallet_balance_total":0.00,\
        "gateway_fees_today":0.00}
        """;
    when(cache.getKpiJson()).thenReturn(Optional.of(cached));

    Map<String, Object> data = service.kpi(finance);

    assertThat(data.get("as_of")).isEqualTo("2026-07-24T15:59:30Z");
    assertThat(data.get("gmv_today")).isEqualTo(100.0);
    verify(store, never()).kpi(any(), any());
  }

  @Test
  void ac002_pnlNetRevenueFormula() {
    when(store.periodTotals(any(), any()))
        .thenReturn(
            new PeriodTotals(
                129_500_000L,
                10_360_000L,
                2_850_000L,
                1_917_500L,
                94_620_000L,
                0,
                1_295_000L,
                3714,
                0,
                3714));
    when(store.gmvChart(any(), any(), eq(ChartGranularity.DAILY)))
        .thenReturn(List.of(new ChartPoint("2026-07-17", 16_200_000L, 465)));

    Map<String, Object> data = service.pnl(finance, "7D", null, null);

    // 103600 - 28500 - 19175 = 55925
    assertThat(data.get("net_revenue")).isEqualTo(new BigDecimal("55925.00"));
    assertThat(data.get("period")).isEqualTo("7D");
    assertThat(data.get("from")).isEqualTo("2026-07-17");
    assertThat(data.get("to")).isEqualTo("2026-07-24");
  }

  @Test
  void ac003_gmvChartHourlyForTodayDailyFor7d() {
    when(store.periodTotals(any(), any()))
        .thenReturn(new PeriodTotals(1000, 100, 0, 0, 0, 0, 0, 1, 0, 1));
    when(store.gmvChart(any(), any(), eq(ChartGranularity.HOURLY)))
        .thenReturn(List.of(new ChartPoint("2026-07-24T10:00:00", 500, 1)));
    when(store.gmvChart(any(), any(), eq(ChartGranularity.DAILY)))
        .thenReturn(List.of(new ChartPoint("2026-07-24", 1000, 1)));

    service.pnl(finance, "TODAY", null, null);
    verify(store).gmvChart(any(), any(), eq(ChartGranularity.HOURLY));

    service.pnl(finance, "7D", null, null);
    verify(store).gmvChart(any(), any(), eq(ChartGranularity.DAILY));
  }

  @Test
  void ac004_cashPositionPlatformNetExcludesWallet() {
    when(store.periodTotals(any(), any()))
        .thenReturn(
            new PeriodTotals(
                555_000_000L,
                0,
                12_200_000L,
                8_200_000L,
                410_000_000L,
                32_000_000L,
                5_550_000L,
                10,
                0,
                10));
    when(store.kpi(any(), any()))
        .thenReturn(new KpiSnapshot(0, 0, 0, 0, 0, 0, 2_845_000L, 125_000_000L, 0));

    Map<String, Object> data = service.cashPosition(finance, "30D", null, null);

    // 5550000 - 4100000 - 320000 - 122000 = 1008000
    assertThat(data.get("platform_net")).isEqualTo(new BigDecimal("1008000.00"));
    assertThat(data.get("held_in_wallet")).isEqualTo(new BigDecimal("1250000.00"));
  }

  @Test
  void ac005_takeRatePct() {
    when(store.periodTotals(any(), any()))
        .thenReturn(new PeriodTotals(1_000_000L, 80_000L, 0, 0, 0, 0, 0, 10, 0, 10));
    when(store.gmvSum(any(), any())).thenReturn(0L);

    Map<String, Object> data = service.ratios(finance, "30D", null, null);

    assertThat(data.get("take_rate_pct")).isEqualTo(new BigDecimal("8.0"));
  }

  @Test
  void ac006_customWithoutDates() {
    assertThatThrownBy(() -> service.pnl(finance, "CUSTOM", null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOM_DATES_REQUIRED");
  }

  @Test
  void ac007_forbiddenForNonFinance() {
    assertThatThrownBy(() -> service.kpi(support))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void invalidPeriodAndDateRangeTooLarge() {
    assertThatThrownBy(() -> service.pnl(finance, "YEAR", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PERIOD");
    assertThatThrownBy(() -> service.pnl(finance, "CUSTOM", "2024-01-01", "2026-01-02"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DATE_RANGE_TOO_LARGE");
  }

  @Test
  void weeklyTrendBranches() {
    when(store.periodTotals(any(), any()))
        .thenReturn(new PeriodTotals(1_000_000L, 0, 0, 0, 0, 0, 0, 10, 4, 10));
    when(store.gmvSum(any(), any())).thenReturn(700_000L, 500_000L);
    assertThat(service.ratios(finance, "30D", null, null).get("weekly_gmv_trend")).isEqualTo("UP");

    when(store.gmvSum(any(), any())).thenReturn(500_000L, 700_000L);
    assertThat(service.ratios(finance, "30D", null, null).get("weekly_gmv_trend"))
        .isEqualTo("DOWN");

    when(store.gmvSum(any(), any())).thenReturn(700_000L, 700_000L);
    assertThat(service.ratios(finance, "30D", null, null).get("weekly_gmv_trend"))
        .isEqualTo("FLAT");

    when(store.gmvSum(any(), any())).thenReturn(100_000L, 0L);
    assertThat(service.ratios(finance, "30D", null, null).get("weekly_gmv_trend")).isEqualTo("UP");
  }

  @Test
  void corruptCacheFallsBackAndNullPrincipalForbidden() {
    when(cache.getKpiJson()).thenReturn(Optional.of("not-json"));
    when(store.kpi(any(), any())).thenReturn(new KpiSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0));
    assertThat(service.kpi(finance).get("gmv_today")).isEqualTo(new BigDecimal("0.00"));

    assertThatThrownBy(() -> service.kpi(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void customWindowAndZeroGmvRatios() {
    when(store.periodTotals(any(), any()))
        .thenReturn(new PeriodTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    when(store.gmvChart(any(), any(), any())).thenReturn(List.of());
    when(store.gmvSum(any(), any())).thenReturn(0L);

    Map<String, Object> pnl = service.pnl(finance, "CUSTOM", "2026-07-01", "2026-07-10");
    assertThat(pnl.get("from")).isEqualTo("2026-07-01");
    assertThat(pnl.get("avg_order_value")).isEqualTo(new BigDecimal("0.00"));

    Map<String, Object> ratios = service.ratios(finance, "CUSTOM", "2026-07-01", "2026-07-10");
    assertThat(ratios.get("take_rate_pct")).isEqualTo(new BigDecimal("0.0"));
  }

  @Test
  void customFromAfterToAndBadDateAndCacheWriteFailure() throws Exception {
    assertThatThrownBy(() -> service.pnl(finance, "CUSTOM", "2026-07-10", "2026-07-01"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PERIOD");
    assertThatThrownBy(() -> service.pnl(finance, "CUSTOM", "not-a-date", "2026-07-01"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOM_DATES_REQUIRED");

    ObjectMapper boom = org.mockito.Mockito.mock(ObjectMapper.class);
    when(boom.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
    when(cache.getKpiJson()).thenReturn(Optional.empty());
    when(store.kpi(any(), any())).thenReturn(new KpiSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0));
    FinanceOverviewService svc =
        new FinanceOverviewService(store, cache, boom, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(svc.kpi(finance)).containsKey("as_of");
  }

  @Test
  void blankPeriodDefaultsTo7dAndWhitespaceCustomFrom() {
    when(store.periodTotals(any(), any()))
        .thenReturn(new PeriodTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    when(store.gmvChart(any(), any(), any())).thenReturn(List.of());
    assertThat(service.pnl(finance, "  ", null, null).get("period")).isEqualTo("7D");
    assertThat(service.pnl(finance, null, null, null).get("period")).isEqualTo("7D");
    assertThatThrownBy(() -> service.pnl(finance, "CUSTOM", "  ", "2026-07-01"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOM_DATES_REQUIRED");
    assertThatThrownBy(() -> service.pnl(finance, "CUSTOM", "2026-07-01", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOM_DATES_REQUIRED");
  }

  @Test
  void superAdmin30d90dAndZeroWeekTrend() {
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    when(cache.getKpiJson()).thenReturn(Optional.empty());
    when(store.kpi(any(), any())).thenReturn(new KpiSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0));
    when(store.periodTotals(any(), any()))
        .thenReturn(new PeriodTotals(100, 10, 0, 0, 0, 0, 0, 10, 4, 0));
    when(store.gmvChart(any(), any(), any())).thenReturn(List.of());
    when(store.gmvSum(any(), any())).thenReturn(0L, 0L);

    assertThat(service.kpi(superAdmin)).containsKey("as_of");
    assertThat(service.pnl(superAdmin, "90D", null, null).get("period")).isEqualTo("90D");
    assertThat(service.cashPosition(superAdmin, "30D", null, null).get("period")).isEqualTo("30D");
    Map<String, Object> ratios = service.ratios(superAdmin, "7D", null, null);
    assertThat(ratios.get("weekly_gmv_trend")).isEqualTo("FLAT");
    assertThat(ratios.get("cod_share_pct")).isEqualTo(new BigDecimal("40.0"));
  }
}
