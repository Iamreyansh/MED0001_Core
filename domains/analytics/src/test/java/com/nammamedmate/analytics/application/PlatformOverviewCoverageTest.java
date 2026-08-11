package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.adapter.out.storage.LocalAnalyticsExportStore;
import com.nammamedmate.analytics.application.port.out.AnalyticsExportPort;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.KpiTotals;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.PharmacyLeader;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.RiderLeader;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.ZoneSalesRow;
import com.nammamedmate.analytics.domain.PeriodResolver;
import com.nammamedmate.analytics.domain.PeriodResolver.DateWindow;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlatformOverviewCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");

  @Mock private PlatformOverviewStore store;
  @Mock private AnalyticsExportPort exports;

  @TempDir Path temp;

  @Test
  @SuppressWarnings("unchecked")
  void coversRemainingBranches() {
    PlatformOverviewService service =
        new PlatformOverviewService(store, exports, Clock.fixed(NOW, ZoneOffset.UTC));
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

    when(store.liveKpis(any(), any()))
        .thenReturn(new KpiTotals(100, 1, 1, 0, 0, 0, 10, 40, 1, 0, 0));
    assertThat(service.overview(superAdmin, "TODAY", null, null).get("data_source"))
        .isEqualTo("LIVE");

    when(store.liveCategoryMix(any(), any())).thenReturn(List.of());
    when(store.livePaymentMix(any(), any())).thenReturn(List.of());
    when(store.liveGmvTrend(any(), any())).thenReturn(List.of());
    when(store.liveSalesByZone(any(), any()))
        .thenReturn(List.of(new ZoneSalesRow(null, "Unknown", 1, 1)));
    Map<String, Object> charts = service.charts(superAdmin, "7D", null, null);
    List<Map<String, Object>> zones = (List<Map<String, Object>>) charts.get("sales_by_zone");
    assertThat(zones.getFirst().get("zone_id")).isNull();

    when(store.topPharmacies(any(), any(), eq(10)))
        .thenReturn(
            List.of(new PharmacyLeader(UUID.randomUUID(), "Plain", null, 4.0, 1, 10, 90.0)));
    when(store.topRiders(any(), any(), eq(10)))
        .thenReturn(
            List.of(new RiderLeader(UUID.randomUUID(), "Name\nX", "Zone", 2, 90.0, 4.0, 100)));
    when(exports.signedGet(any(), any()))
        .thenReturn(new AnalyticsExportPort.SignedUrl("u", NOW.plusSeconds(1)));
    service.leaderboards(superAdmin, "7D", null, "csv");
    verify(exports).put(any(), any(), eq("text/csv"));

    when(store.topPharmacies(any(), any(), eq(10))).thenReturn(List.of());
    when(store.topRiders(any(), any(), eq(10))).thenReturn(List.of());
    assertThat(service.leaderboards(superAdmin, "7D", null, "").get("export_url")).isNull();

    when(store.aggregatedKpis(any(), any()))
        .thenReturn(new KpiTotals(1, 1, 1, 0, 0, 0, 1, 0, 1, 0, 0));
    assertThat(
            service.overview(superAdmin, "CUSTOM", "2026-01-01", "2026-04-01").get("data_source"))
        .isEqualTo("AGGREGATED");
  }

  @Test
  void periodResolverEdgeBranches() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    DateWindow today = PeriodResolver.resolveOverview("TODAY", null, null, clock);
    Instant farFrom = today.fromInclusive().minus(Duration.ofDays(3));
    DateWindow stretched =
        new DateWindow(
            "TODAY", today.fromDate(), today.toDate(), farFrom, today.toExclusive(), true);
    DateWindow prior = stretched.priorWindow(clock);
    assertThat(prior.toExclusive())
        .isBeforeOrEqualTo(
            prior.fromDate().plusDays(1).atStartOfDay(PeriodResolver.IST).toInstant());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> PeriodResolver.resolveOverview("CUSTOM", "x", "2026-07-01", clock))
        .isInstanceOf(AppException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> PeriodResolver.resolveOverview("CUSTOM", "2026-07-01", " ", clock))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MISSING_DATE_RANGE");

    DateWindow custom90 =
        PeriodResolver.resolveOverview("CUSTOM", "2026-01-01", "2026-04-01", clock);
    assertThat(PeriodResolver.useAggregated(custom90)).isTrue();
  }

  @Test
  void exportStorePrefixedKey() {
    LocalAnalyticsExportStore store = new LocalAnalyticsExportStore(temp, "file://" + temp);
    var signed = store.signedGet("exports/already.csv", Duration.ofSeconds(10));
    assertThat(signed.url()).contains("exports-already.csv");
  }
}
