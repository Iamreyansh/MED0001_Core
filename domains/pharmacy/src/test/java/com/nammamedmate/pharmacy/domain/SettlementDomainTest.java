package com.nammamedmate.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SettlementDomainTest {

  @Test
  void settlementCalculator_tcsThresholdAndNet() {
    var above =
        SettlementCalculator.compute(
            100_000_00L, new BigDecimal("8.00"), SettlementCalculator.TCS_THRESHOLD_PAISE + 1);
    assertThat(above.tcsApplicable()).isTrue();
    assertThat(above.tcsDeductedPaise()).isEqualTo(1_000_00L);
    assertThat(above.commissionEarnedPaise()).isEqualTo(8_000_00L);
    assertThat(above.netPaidPaise()).isEqualTo(91_000_00L);

    var below = SettlementCalculator.compute(100_000_00L, new BigDecimal("8.00"), 1_000_00L);
    assertThat(below.tcsApplicable()).isTrue();
    assertThat(below.tcsDeductedPaise()).isEqualTo(1_000_00L);
    assertThat(below.netPaidPaise()).isEqualTo(91_000_00L);
    assertThat(SettlementCalculator.tdsThresholdCrossed(1_000_00L)).isFalse();
    assertThat(
            SettlementCalculator.tdsThresholdCrossed(SettlementCalculator.TCS_THRESHOLD_PAISE + 1))
        .isTrue();
  }

  @Test
  void settlementPeriod_weekBoundaries() {
    LocalDate thursday = LocalDate.parse("2026-07-23");
    assertThat(SettlementPeriod.weekMonday(thursday)).isEqualTo(LocalDate.parse("2026-07-20"));
    assertThat(SettlementPeriod.weekSunday(thursday)).isEqualTo(LocalDate.parse("2026-07-26"));
    assertThat(SettlementPeriod.previousWeekMonday(thursday))
        .isEqualTo(LocalDate.parse("2026-07-13"));
    assertThat(SettlementPeriod.label(LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-20")))
        .isEqualTo("2026-07-14 to 2026-07-20");
  }
}
