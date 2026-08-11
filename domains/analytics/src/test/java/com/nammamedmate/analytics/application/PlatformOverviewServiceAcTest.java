package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.AnalyticsExportPort;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.CategoryMixRow;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.GmvTrendPoint;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.KpiTotals;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.PaymentMixRow;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.PharmacyLeader;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.RiderLeader;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.ZoneSalesRow;
import com.nammamedmate.analytics.domain.AnalyticsMath;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlatformOverviewServiceAcTest {

  /** 2026-07-24 16:00 UTC ≈ 21:30 IST */
  private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");

  @Mock private PlatformOverviewStore store;
  @Mock private AnalyticsExportPort exports;

  private PlatformOverviewService service;
  private MedmatePrincipal ops;
  private MedmatePrincipal support;

  @BeforeEach
  void setUp() {
    service = new PlatformOverviewService(store, exports, Clock.fixed(NOW, ZoneOffset.UTC));
    ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac001_overview7dReturnsEightKpisWithWow() {
    KpiTotals current =
        new KpiTotals(
            4_820_000, 3841, 3500, 100, 100_000, 140_000, 684_440, 3_700_000, 2140, 824, 0);
    KpiTotals prior =
        new KpiTotals(4_290_000, 3550, 3200, 90, 90_000, 120_000, 600_000, 3_400_000, 2020, 750, 0);
    when(store.liveKpis(any(), any())).thenReturn(current, prior);

    Map<String, Object> data = service.overview(ops, "7D", null, null);

    assertThat(data.get("period")).isEqualTo("7D");
    Map<String, Object> kpis = (Map<String, Object>) data.get("kpis");
    assertThat(kpis)
        .containsKeys(
            "gmv",
            "orders_count",
            "aov",
            "net_revenue",
            "net_margin_pct",
            "take_rate_pct",
            "active_customers",
            "repeat_customer_pct");
    for (String key : kpis.keySet()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> card = (Map<String, Object>) kpis.get(key);
      assertThat(card).containsKey("wow_delta_pct");
    }
    assertThat(data.get("data_source")).isEqualTo("LIVE");
  }

  @Test
  void ac002_todayUsesLiveDataSource() {
    when(store.liveKpis(any(), any()))
        .thenReturn(new KpiTotals(1000, 2, 1, 0, 0, 0, 100, 500, 2, 0, 0));

    Map<String, Object> data = service.overview(ops, "TODAY", null, null);

    assertThat(data.get("data_source")).isEqualTo("LIVE");
    verify(store, never()).aggregatedKpis(any(), any());
    verify(store, org.mockito.Mockito.atLeastOnce()).liveKpis(any(), any());
  }

  @Test
  void ac003_ninetyDayUsesAggregatedSnapshots() {
    when(store.aggregatedKpis(any(), any()))
        .thenReturn(new KpiTotals(10_000, 10, 8, 1, 100, 200, 1400, 7000, 5, 2, 1));

    Map<String, Object> data = service.overview(ops, "90D", null, null);

    assertThat(data.get("data_source")).isEqualTo("AGGREGATED");
    verify(store, org.mockito.Mockito.atLeastOnce()).aggregatedKpis(any(), any());
    verify(store, never()).liveKpis(any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac004_chartsGmvTrendOnePointPerDay() {
    when(store.liveGmvTrend(any(), any()))
        .thenReturn(
            List.of(
                new GmvTrendPoint(LocalDate.of(2026, 7, 17), 100),
                new GmvTrendPoint(LocalDate.of(2026, 7, 24), 200)));
    when(store.liveCategoryMix(any(), any())).thenReturn(List.of());
    when(store.livePaymentMix(any(), any())).thenReturn(List.of());
    when(store.liveSalesByZone(any(), any())).thenReturn(List.of());

    Map<String, Object> data = service.charts(ops, "7D", null, null);

    List<Map<String, Object>> trend = (List<Map<String, Object>>) data.get("gmv_trend");
    assertThat(trend).hasSize(8);
    assertThat(trend.getFirst().get("date")).isEqualTo("2026-07-17");
    assertThat(trend.getLast().get("date")).isEqualTo("2026-07-24");
    assertThat(trend.get(1).get("gmv_paise")).isEqualTo(0L);
  }

  @Test
  void ac005_leaderboardCsvExportReturnsUrl() {
    when(store.topPharmacies(any(), any(), eq(10))).thenReturn(List.of());
    when(store.topRiders(any(), any(), eq(10))).thenReturn(List.of());
    when(exports.signedGet(any(), eq(Duration.ofHours(1))))
        .thenReturn(
            new AnalyticsExportPort.SignedUrl(
                "file:///tmp/export.csv?ttl=3600", NOW.plusSeconds(3600)));

    Map<String, Object> data = service.leaderboards(ops, "7D", null, "csv");

    assertThat(data.get("export_url")).isEqualTo("file:///tmp/export.csv?ttl=3600");
    verify(exports).put(any(), any(), eq("text/csv"));
  }

  @Test
  void ac006_adminSupportForbiddenOnOverview() {
    assertThatThrownBy(() -> service.overview(support, "7D", null, null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("FORBIDDEN");
              assertThat(ae.httpStatus()).isEqualTo(403);
            });
    assertThatThrownBy(() -> service.charts(support, "7D", null, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.leaderboards(support, "7D", 10, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void ac007_customWithoutDatesMissingDateRange() {
    assertThatThrownBy(() -> service.overview(ops, "CUSTOM", null, null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("MISSING_DATE_RANGE");
              assertThat(ae.httpStatus()).isEqualTo(400);
            });
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac008_takeRateMatchesCommissionOverGmvTimes100() {
    long gmv = 4_820_000;
    long commission = 684_440;
    when(store.liveKpis(any(), any()))
        .thenReturn(new KpiTotals(gmv, 10, 8, 0, 0, 0, commission, 1000, 5, 2, 0));

    Map<String, Object> data = service.overview(ops, "7D", null, null);
    Map<String, Object> kpis = (Map<String, Object>) data.get("kpis");
    Map<String, Object> take = (Map<String, Object>) kpis.get("take_rate_pct");
    BigDecimal expected = AnalyticsMath.takeRatePct(commission, gmv);
    BigDecimal actual = (BigDecimal) take.get("value");
    assertThat(actual.doubleValue()).isCloseTo(expected.doubleValue(), within(0.01));
  }

  @Test
  void invalidPeriodRejected() {
    assertThatThrownBy(() -> service.overview(ops, "YEAR", null, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
  }

  @Test
  void customRangeTooLarge() {
    assertThatThrownBy(() -> service.overview(ops, "CUSTOM", "2025-01-01", "2026-07-01"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("DATE_RANGE_TOO_LARGE");
              assertThat(ae.httpStatus()).isEqualTo(422);
            });
  }

  @Test
  void exportTooLargeWhenTopNExceeds50() {
    assertThatThrownBy(() -> service.leaderboards(ops, "7D", 51, "csv"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("EXPORT_TOO_LARGE");
              assertThat(ae.httpStatus()).isEqualTo(422);
            });
  }

  @Test
  void leaderboardTodayInvalid() {
    assertThatThrownBy(() -> service.leaderboards(ops, "TODAY", 10, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
  }

  @Test
  void leaderboardsPopulateRanksAndNullExportUrl() {
    UUID ph = UUID.randomUUID();
    UUID rid = UUID.randomUUID();
    when(store.topPharmacies(any(), any(), eq(5)))
        .thenReturn(
            List.of(new PharmacyLeader(ph, "A Pharmacy", "Indiranagar", 4.8, 10, 1000, 96.2)));
    when(store.topRiders(any(), any(), eq(5)))
        .thenReturn(List.of(new RiderLeader(rid, "Ramesh", "Indiranagar", 5, 97.8, 4.9, 500)));

    Map<String, Object> data = service.leaderboards(ops, "30D", 5, null);

    assertThat(data.get("export_url")).isNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pharmacies = (List<Map<String, Object>>) data.get("top_pharmacies");
    assertThat(pharmacies.getFirst().get("rank")).isEqualTo(1);
    assertThat(pharmacies.getFirst().get("pharmacy_id")).isEqualTo(ph.toString());
  }

  @Test
  void chartsAggregatedPathFor90d() {
    when(store.aggregatedGmvTrend(any(), any())).thenReturn(List.of());
    when(store.aggregatedCategoryMix(any(), any()))
        .thenReturn(List.of(new CategoryMixRow("OTC_MEDICINES", 100)));
    when(store.aggregatedPaymentMix(any(), any())).thenReturn(List.of(new PaymentMixRow("UPI", 3)));
    when(store.aggregatedSalesByZone(any(), any()))
        .thenReturn(List.of(new ZoneSalesRow(UUID.randomUUID(), "Indiranagar", 50, 2)));

    Map<String, Object> data = service.charts(ops, "90D", null, null);

    assertThat(data.get("gmv_trend")).asList().hasSize(91);
    verify(store).aggregatedGmvTrend(any(), any());
  }

  @Test
  void customValidRangeUsesLiveWhenUnder90Days() {
    when(store.liveKpis(any(), any()))
        .thenReturn(new KpiTotals(100, 1, 1, 0, 0, 0, 10, 50, 1, 0, 0));

    Map<String, Object> data = service.overview(ops, "CUSTOM", "2026-07-01", "2026-07-10");

    assertThat(data.get("data_source")).isEqualTo("LIVE");
    ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
    verify(store, org.mockito.Mockito.atLeastOnce()).liveKpis(from.capture(), any());
  }

  @Test
  void financeRoleForbidden() {
    MedmatePrincipal finance =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.overview(finance, "7D", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void topNTooLargeWithoutExportInvalid() {
    assertThatThrownBy(() -> service.leaderboards(ops, "7D", 99, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
  }

  @Test
  void topNLessThanOneInvalid() {
    assertThatThrownBy(() -> service.leaderboards(ops, "7D", 0, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
  }

  @Test
  void badExportModeRejected() {
    when(store.topPharmacies(any(), any(), anyInt())).thenReturn(List.of());
    when(store.topRiders(any(), any(), anyInt())).thenReturn(List.of());
    assertThatThrownBy(() -> service.leaderboards(ops, "7D", 10, "pdf"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
  }

  @Test
  void nullPrincipalForbidden() {
    assertThatThrownBy(() -> service.overview(null, "7D", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void customFromAfterToInvalid() {
    assertThatThrownBy(() -> service.overview(ops, "CUSTOM", "2026-07-20", "2026-07-10"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
  }

  @Test
  void blankPeriodInvalid() {
    assertThatThrownBy(() -> service.overview(ops, " ", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
  }

  @Test
  void csvEscapesCommasInNames() {
    when(store.topPharmacies(any(), any(), eq(10)))
        .thenReturn(
            List.of(
                new PharmacyLeader(
                    UUID.randomUUID(), "Apollo, Indiranagar", "Area \"A\"", 4.5, 1, 100, 90)));
    when(store.topRiders(any(), any(), eq(10))).thenReturn(List.of());
    when(exports.signedGet(any(), any()))
        .thenReturn(new AnalyticsExportPort.SignedUrl("u", NOW.plusSeconds(1)));

    service.leaderboards(ops, "7D", 10, "csv");

    ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
    verify(exports).put(any(), bytes.capture(), eq("text/csv"));
    String csv = new String(bytes.getValue());
    assertThat(csv).contains("\"Apollo, Indiranagar\"");
    assertThat(csv).contains("\"Area \"\"A\"\"\"");
  }
}
