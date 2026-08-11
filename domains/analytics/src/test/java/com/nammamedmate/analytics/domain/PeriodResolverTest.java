package com.nammamedmate.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.analytics.domain.PeriodResolver.DateWindow;
import com.nammamedmate.kernel.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PeriodResolverTest {

  private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void resolvesPresetsAndPriorWindows() {
    DateWindow seven = PeriodResolver.resolveOverview("7D", null, null, clock);
    assertThat(seven.fromDate()).isEqualTo(LocalDate.of(2026, 7, 17));
    assertThat(seven.toDate()).isEqualTo(LocalDate.of(2026, 7, 24));
    assertThat(seven.dayCount()).isEqualTo(8);
    assertThat(PeriodResolver.useAggregated(seven)).isFalse();

    DateWindow prior = seven.priorWindow(clock);
    assertThat(prior.toDate()).isEqualTo(LocalDate.of(2026, 7, 16));
    assertThat(prior.fromDate()).isEqualTo(LocalDate.of(2026, 7, 9));

    DateWindow today = PeriodResolver.resolveOverview("TODAY", null, null, clock);
    assertThat(today.live()).isTrue();
    assertThat(today.priorWindow(clock).fromDate()).isEqualTo(LocalDate.of(2026, 7, 17));

    DateWindow ninety = PeriodResolver.resolveOverview("90D", null, null, clock);
    assertThat(PeriodResolver.useAggregated(ninety)).isTrue();

    DateWindow lb = PeriodResolver.resolveLeaderboard("30D", clock);
    assertThat(lb.period()).isEqualTo("30D");

    DateWindow growth = PeriodResolver.resolveGrowth("30D", null, null, clock);
    assertThat(growth.period()).isEqualTo("30D");
    assertThatThrownBy(() -> PeriodResolver.resolveGrowth("TODAY", null, null, clock))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");

    DateWindow trend = PeriodResolver.resolveGrowthTrend("7D", clock);
    assertThat(trend.period()).isEqualTo("7D");

    DateWindow fy = PeriodResolver.resolvePharmacy("FY", null, null, clock);
    assertThat(fy.fromDate()).isEqualTo(LocalDate.of(2026, 4, 1));
    assertThat(fy.toDate()).isEqualTo(LocalDate.of(2026, 7, 24));
    assertThat(PeriodResolver.useAggregated(fy)).isTrue();

    DateWindow twelve = PeriodResolver.resolvePharmacy("12M", null, null, clock);
    assertThat(twelve.period()).isEqualTo("12M");
    assertThat(PeriodResolver.useAggregated(twelve)).isTrue();

    assertThatThrownBy(() -> PeriodResolver.resolvePharmacy("TODAY", null, null, clock))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");

    // Before Apr 1 → previous FY start
    Clock feb = Clock.fixed(Instant.parse("2026-02-15T10:00:00Z"), ZoneOffset.UTC);
    DateWindow fyPrev = PeriodResolver.resolvePharmacy("FY", null, null, feb);
    assertThat(fyPrev.fromDate()).isEqualTo(LocalDate.of(2025, 4, 1));
  }

  @Test
  void customAndErrors() {
    DateWindow custom =
        PeriodResolver.resolveOverview("CUSTOM", "2026-07-01T00:00:00Z", "2026-07-05", clock);
    assertThat(custom.dayCount()).isEqualTo(5);
    assertThat(custom.dateToDisplayInstant()).isBefore(custom.toExclusive());

    assertThatThrownBy(() -> PeriodResolver.resolveOverview("CUSTOM", "bad", "2026-07-01", clock))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MISSING_DATE_RANGE");

    assertThatThrownBy(() -> PeriodResolver.resolveLeaderboard("TODAY", clock))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");

    assertThatThrownBy(() -> PeriodResolver.resolveOverview(null, null, null, clock))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");

    DateWindow geo = PeriodResolver.resolveGeography("30D", clock);
    assertThat(geo.period()).isEqualTo("30D");
    assertThatThrownBy(() -> PeriodResolver.resolveGeography("90D", clock))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
    DateWindow gap = PeriodResolver.resolveGeographyGap("7D", clock);
    assertThat(gap.period()).isEqualTo("7D");
    assertThatThrownBy(() -> PeriodResolver.resolveGeographyGap("TODAY", clock))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
  }
}
