package com.nammamedmate.observability_ops.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ErrorBudgetTest {

  @Test
  void matchesSamplePayloadsAndExhaustion() {
    assertThat(ErrorBudget.remainingPct(new BigDecimal("99"), new BigDecimal("99.4")))
        .isEqualByComparingTo("140.0");
    assertThat(ErrorBudget.remainingPct(new BigDecimal("98"), new BigDecimal("97.8")))
        .isEqualByComparingTo("90.0");
    assertThat(ErrorBudget.remainingPct(new BigDecimal("100"), new BigDecimal("99.2")))
        .isEqualByComparingTo("20.0");
    // order_sla sample listed 74; standard formula yields 64 (documented quirk)
    assertThat(ErrorBudget.remainingPct(new BigDecimal("95"), new BigDecimal("93.2")))
        .isEqualByComparingTo("64.0");
    assertThat(ErrorBudget.remainingPct(new BigDecimal("95"), new BigDecimal("90.0")))
        .isEqualByComparingTo("0.0");
    assertThat(ErrorBudget.exhausted(BigDecimal.ZERO)).isTrue();
    assertThat(ErrorBudget.exhausted(new BigDecimal("-1"))).isTrue();
    assertThat(ErrorBudget.exhausted(new BigDecimal("1"))).isFalse();
    assertThat(ErrorBudget.consumedPct(new BigDecimal("99"), new BigDecimal("99.4")))
        .isEqualByComparingTo("-40.0");
    assertThat(ErrorBudget.consumedPct(new BigDecimal("95"), new BigDecimal("90.0")))
        .isEqualByComparingTo("100.0");
  }
}
