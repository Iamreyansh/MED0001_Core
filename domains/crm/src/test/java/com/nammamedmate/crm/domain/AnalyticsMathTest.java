package com.nammamedmate.crm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnalyticsMathTest {

  @Test
  @DisplayName("AC-002 ARR = MRR × 12")
  void arr() {
    assertThat(AnalyticsMath.arrPaise(61248000L)).isEqualTo(734976000L);
  }

  @Test
  @DisplayName("AC-003 NRR > 100 when expansion > churn")
  void nrr() {
    BigDecimal nrr = AnalyticsMath.nrrPct(10000L, 2000L, 500L);
    assertThat(nrr).isEqualByComparingTo("115.00");
    assertThat(nrr).isGreaterThan(BigDecimal.valueOf(100));
    assertThat(AnalyticsMath.nrrPct(0, 1, 1)).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("AC-004 GRR ≤ NRR")
  void grr() {
    BigDecimal nrr = AnalyticsMath.nrrPct(57520000L, 1120000L, 876000L);
    BigDecimal grr = AnalyticsMath.grrPct(57520000L, 876000L);
    assertThat(grr).isLessThanOrEqualTo(nrr);
    assertThat(AnalyticsMath.grrPct(0, 1)).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("AC-005 quick ratio > 1 when new+expansion > contraction+churn")
  void quickRatio() {
    assertThat(AnalyticsMath.quickRatio(38640, 11200, 3800, 8760)).isGreaterThan(BigDecimal.ONE);
    assertThat(AnalyticsMath.quickRatio(1, 1, 0, 0)).isEqualByComparingTo("0.00");
  }

  @Test
  void magicLtvCacPayback() {
    assertThat(AnalyticsMath.magicNumber(10000, 0)).isNull();
    assertThat(AnalyticsMath.magicNumber(25000000L, 150000000L)).isEqualByComparingTo("0.67");
    assertThat(AnalyticsMath.arpaPaise(1000, 0)).isZero();
    assertThat(AnalyticsMath.arpaPaise(1000, 4)).isEqualTo(250);
    assertThat(
            AnalyticsMath.ltvPaise(0, AnalyticsMath.DEFAULT_GROSS_MARGIN_PCT, new BigDecimal("1")))
        .isZero();
    assertThat(AnalyticsMath.ltvPaise(102400, AnalyticsMath.DEFAULT_GROSS_MARGIN_PCT, null))
        .isZero();
    assertThat(
            AnalyticsMath.ltvPaise(102400, AnalyticsMath.DEFAULT_GROSS_MARGIN_PCT, BigDecimal.ZERO))
        .isZero();
    assertThat(AnalyticsMath.ltvPaise(102400, null, new BigDecimal("1.43"))).isZero();
    assertThat(AnalyticsMath.ltvPaise(102400, BigDecimal.ZERO, new BigDecimal("1.43"))).isZero();
    assertThat(
            AnalyticsMath.ltvPaise(
                102400, AnalyticsMath.DEFAULT_GROSS_MARGIN_PCT, new BigDecimal("1.43")))
        .isGreaterThan(0);
    assertThat(AnalyticsMath.cacPaise(100, 0)).isZero();
    assertThat(AnalyticsMath.cacPaise(0, 10)).isZero();
    assertThat(AnalyticsMath.cacPaise(585000, 100)).isEqualTo(5850);
    assertThat(AnalyticsMath.ltvCacRatio(32768, 5850)).isEqualByComparingTo("5.6");
    assertThat(AnalyticsMath.ltvCacRatio(1, 0)).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.paybackMonths(585000, 102400, AnalyticsMath.DEFAULT_GROSS_MARGIN_PCT))
        .isGreaterThan(BigDecimal.ZERO);
    assertThat(AnalyticsMath.paybackMonths(0, 1, AnalyticsMath.DEFAULT_GROSS_MARGIN_PCT))
        .isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.paybackMonths(100, 0, AnalyticsMath.DEFAULT_GROSS_MARGIN_PCT))
        .isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.paybackMonths(100, 50, null)).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.paybackMonths(100, 50, BigDecimal.ZERO)).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.mrrGrowthPct(110, 100)).isEqualByComparingTo("10.0");
    assertThat(AnalyticsMath.mrrGrowthPct(110, 0)).isEqualByComparingTo("0.0");
    assertThat(AnalyticsMath.retentionPct(48, 48)).isEqualByComparingTo("100.00");
    assertThat(AnalyticsMath.retentionPct(1, 0)).isEqualByComparingTo("0.00");
    assertThat(AnalyticsMath.netNewMrrPaise(10, 5, 2, 3)).isEqualTo(10);
  }
}
