package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.AcquisitionRow;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GrowthAnalyticsCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");

  @Mock private PlatformGrowthStore store;

  @Test
  @SuppressWarnings("unchecked")
  void coversNullPrincipalAggregatedEmptyAcquisitionAndCacBranches() {
    GrowthAnalyticsService service =
        new GrowthAnalyticsService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> service.growth(null, "30D", null, null))
        .isInstanceOf(AppException.class);

    MedmatePrincipal superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    when(store.aggregatedGrowth(any(), any())).thenReturn(new GrowthTotals(0, 0, 0));
    when(store.month1Retention(any()))
        .thenReturn(Optional.of(new Month1Retention("2026-W10", new BigDecimal("38.50"))))
        .thenReturn(Optional.empty());
    Map<String, Object> growth = service.growth(superAdmin, "90D", null, null);
    Map<String, Object> month1 =
        (Map<String, Object>)
            ((Map<String, Object>) growth.get("kpis")).get("month1_retention_pct");
    assertThat((BigDecimal) month1.get("value")).isEqualByComparingTo("38.5");

    when(store.aggregatedAcquisition(any(), any())).thenReturn(List.of());
    when(store.campaignSpend(any(), any())).thenReturn(List.of());
    Map<String, Object> emptyAcq = service.acquisition(superAdmin, "90D", null, null);
    assertThat((List<?>) emptyAcq.get("sources")).isEmpty();

    when(store.aggregatedAcquisition(any(), any()))
        .thenReturn(
            List.of(
                new AcquisitionRow("AD", 10, 20, 1000), new AcquisitionRow("PARTNER", 0, 0, 0)));
    when(store.campaignSpend(any(), any()))
        .thenReturn(List.of(new SpendRow("AD", new BigDecimal("1800"))));
    Map<String, Object> acq = service.acquisition(superAdmin, "90D", null, null);
    List<Map<String, Object>> sources = (List<Map<String, Object>>) acq.get("sources");
    assertThat(sources.getFirst().get("cac_rs")).isEqualTo(180L);

    when(store.aggregatedAcquisition(any(), any()))
        .thenReturn(List.of(new AcquisitionRow("AD", 0, 1, 0)));
    Map<String, Object> zeroUsers = service.acquisition(superAdmin, "90D", null, null);
    List<Map<String, Object>> zeroSrc = (List<Map<String, Object>>) zeroUsers.get("sources");
    assertThat((BigDecimal) zeroSrc.getFirst().get("pct")).isEqualByComparingTo("0.0");

    when(store.orderTrendDaily(any(), any()))
        .thenReturn(List.of(new OrderTrendPoint(LocalDate.of(2026, 7, 24), 0, 0, 0)));
    assertThat(service.orderTrend(superAdmin, "7D", null).get("granularity")).isEqualTo("DAILY");
    assertThat(service.orderTrend(superAdmin, "7D", "  ").get("granularity")).isEqualTo("DAILY");

    when(store.cohortMatrix(2)).thenReturn(List.of());
    when(store.cohortLastComputedAt()).thenReturn(Optional.empty());
    assertThat(service.cohort(superAdmin, 2).get("last_computed_at")).isNull();

    assertThatThrownBy(() -> service.cohort(superAdmin, 0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
  }

  @Test
  void cohortRefreshDelegates() {
    CohortRefreshService refresh =
        new CohortRefreshService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    refresh.refreshWeekly();
    verify(store).refreshCohortRetention(26, NOW);
    verify(store).refreshAcquisitionDaily(LocalDate.of(2026, 4, 25), LocalDate.of(2026, 7, 24));
  }
}
