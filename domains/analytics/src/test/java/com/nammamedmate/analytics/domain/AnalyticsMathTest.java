package com.nammamedmate.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnalyticsMathTest {

  @Test
  void ratioAndWowHandleZeroDenom() {
    assertThat(AnalyticsMath.ratioPct(10, 0)).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.wowDeltaPct(0, 0)).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.wowDeltaPct(5, 0)).isEqualByComparingTo("100.0");
    assertThat(AnalyticsMath.wowDeltaPct(110, 100)).isEqualByComparingTo("10.0");
  }

  @Test
  void takeRepeatMarginAovNet() {
    assertThat(AnalyticsMath.takeRatePct(142, 1000)).isEqualByComparingTo("14.2");
    assertThat(AnalyticsMath.repeatCustomerPct(385, 1000)).isEqualByComparingTo("38.5");
    assertThat(AnalyticsMath.netMarginPct(1000, 816)).isEqualByComparingTo("18.4");
    assertThat(AnalyticsMath.netMarginPct(0, 10)).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.aovPaise(1000, 0)).isZero();
    assertThat(AnalyticsMath.aovPaise(1000, 4)).isEqualTo(250);
    assertThat(AnalyticsMath.netRevenuePaise(1000, 100, 50)).isEqualTo(850);
    assertThat(AnalyticsMath.netRevenuePaise(10, 20, 0)).isZero();
  }

  @Test
  void indianFyStart() {
    assertThat(AnalyticsMath.indianFyStart(LocalDate.of(2026, 7, 24)))
        .isEqualTo(LocalDate.of(2026, 4, 1));
    assertThat(AnalyticsMath.indianFyStart(LocalDate.of(2026, 3, 1)))
        .isEqualTo(LocalDate.of(2025, 4, 1));
  }

  @Test
  void dropOffAndAvgMinutes() {
    assertThat(AnalyticsMath.dropOffPct(null, 100)).isNull();
    assertThat(AnalyticsMath.dropOffPct(0L, 0)).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.dropOffPct(100L, 97)).isEqualByComparingTo("3.0");
    assertThat(AnalyticsMath.avgMinutes(0, 0)).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.avgMinutes(492, 1)).isEqualByComparingTo("8.2");
  }

  @Test
  void cacRetentionAndIsoWeekHelpers() {
    assertThat(AnalyticsMath.cacRs(null, 10)).isZero();
    assertThat(AnalyticsMath.cacRs(java.math.BigDecimal.ZERO, 10)).isZero();
    assertThat(AnalyticsMath.cacRs(java.math.BigDecimal.TEN, 0)).isZero();
    assertThat(AnalyticsMath.cacRs(new java.math.BigDecimal("1800"), 10)).isEqualTo(180L);
    assertThat(AnalyticsMath.retentionPct(50, 0)).isEqualByComparingTo("0.00");
    assertThat(AnalyticsMath.retentionPct(137, 284)).isEqualByComparingTo("48.24");
    assertThat(AnalyticsMath.isoWeekLabel(LocalDate.of(2026, 4, 20))).isEqualTo("2026-W17");
    assertThat(AnalyticsMath.isoWeekStart(LocalDate.of(2026, 7, 24)))
        .isEqualTo(LocalDate.of(2026, 7, 20));
    assertThat(AnalyticsMath.firstIsoWeekOfMonth(LocalDate.of(2026, 7, 15)))
        .isEqualTo(LocalDate.of(2026, 6, 29));
  }

  @Test
  void geographyGapHelpers() {
    assertThat(AnalyticsMath.gapPct(null, bd("1"))).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.gapPct(bd("0"), bd("1"))).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.gapPct(bd("10"), bd("12"))).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.gapPct(bd("8.4"), bd("2.1"))).isEqualByComparingTo("75.0");
    assertThat(AnalyticsMath.gapPct(bd("5"), null)).isEqualByComparingTo("100.0");

    assertThat(AnalyticsMath.gapSeverity(bd("51"), false)).isEqualTo("CRITICAL");
    assertThat(AnalyticsMath.gapSeverity(bd("50"), false)).isEqualTo("HIGH");
    assertThat(AnalyticsMath.gapSeverity(bd("30"), false)).isEqualTo("HIGH");
    assertThat(AnalyticsMath.gapSeverity(bd("10"), false)).isEqualTo("MODERATE");
    assertThat(AnalyticsMath.gapSeverity(bd("9.9"), false)).isEqualTo("LOW");
    assertThat(AnalyticsMath.gapSeverity(bd("0"), true)).isEqualTo("CRITICAL");
    assertThat(AnalyticsMath.gapSeverity(null, false)).isEqualTo("LOW");

    assertThat(AnalyticsMath.supplyGapSuggestion("HIGH", bd("45"), 0)).isEqualTo("ADD_PHARMACIES");
    assertThat(AnalyticsMath.supplyGapSuggestion("LOW", bd("90"), 2)).isEqualTo("EXPAND_ZONE");
    assertThat(AnalyticsMath.supplyGapSuggestion("CRITICAL", bd("88"), 0)).isEqualTo("ADD_RIDERS");
    assertThat(AnalyticsMath.supplyGapSuggestion("HIGH", bd("70"), 0)).isNull();
    assertThat(AnalyticsMath.supplyGapSuggestion("LOW", bd("90"), 0)).isNull();

    assertThat(AnalyticsMath.demandScore(192, 8)).isEqualByComparingTo("1.0");
    assertThat(AnalyticsMath.demandScore(10, 0)).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.supplyScore(bd("2.0"), bd("50"))).isEqualByComparingTo("1.0");
    assertThat(AnalyticsMath.supplyScore(null, null)).isEqualByComparingTo("0.0");

    assertThat(AnalyticsMath.dayOfWeekName(0)).isEqualTo("SUNDAY");
    assertThat(AnalyticsMath.dayOfWeekName(1)).isEqualTo("MONDAY");
    assertThat(AnalyticsMath.dayOfWeekName(2)).isEqualTo("TUESDAY");
    assertThat(AnalyticsMath.dayOfWeekName(3)).isEqualTo("WEDNESDAY");
    assertThat(AnalyticsMath.dayOfWeekName(4)).isEqualTo("THURSDAY");
    assertThat(AnalyticsMath.dayOfWeekName(5)).isEqualTo("FRIDAY");
    assertThat(AnalyticsMath.dayOfWeekName(6)).isEqualTo("SATURDAY");
    assertThat(AnalyticsMath.dayOfWeekName(null)).isNull();
    assertThat(AnalyticsMath.dayOfWeekName(-1)).isNull();
    assertThat(AnalyticsMath.dayOfWeekName(9)).isNull();
  }

  private static java.math.BigDecimal bd(String v) {
    return new java.math.BigDecimal(v);
  }
}
