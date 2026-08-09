package com.nammamedmate.crm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HealthMathTest {

  @Test
  @DisplayName("AC-009 health_band maps 75/50/25 thresholds")
  void bands() {
    assertThat(HealthBand.fromScore(75)).isEqualTo(HealthBand.HEALTHY);
    assertThat(HealthBand.fromScore(100)).isEqualTo(HealthBand.HEALTHY);
    assertThat(HealthBand.fromScore(74.99)).isEqualTo(HealthBand.MODERATE);
    assertThat(HealthBand.fromScore(50)).isEqualTo(HealthBand.MODERATE);
    assertThat(HealthBand.fromScore(49.99)).isEqualTo(HealthBand.AT_RISK);
    assertThat(HealthBand.fromScore(25)).isEqualTo(HealthBand.AT_RISK);
    assertThat(HealthBand.fromScore(24.99)).isEqualTo(HealthBand.CHURNING);
    assertThat(HealthBand.fromScore(0)).isEqualTo(HealthBand.CHURNING);
  }

  @Test
  void overallWeightsAndBilling() {
    assertThat(HealthMath.overall(100, 100, 100, 100)).isEqualTo(100.0);
    assertThat(HealthMath.overall(100, 0, 100, 100)).isEqualTo(75.0);
    assertThat(HealthMath.billingHealth(List.of())).isEqualTo(100.0);
    assertThat(HealthMath.billingHealth(List.of(InvoiceStatus.PAID))).isEqualTo(100.0);
    assertThat(HealthMath.billingHealth(List.of(InvoiceStatus.DUE))).isEqualTo(70.0);
    assertThat(HealthMath.billingHealth(List.of(InvoiceStatus.DUE, InvoiceStatus.OVERDUE)))
        .isEqualTo(0.0);
    assertThat(HealthMath.billingHealth(List.of(InvoiceStatus.DUNNING))).isEqualTo(0.0);
    assertThat(HealthMath.billingHealth(null)).isEqualTo(100.0);
  }

  @Test
  void businessGrowthAndRiskActions() {
    assertThat(HealthMath.businessFromInvoiceGrowth(0, 0)).isEqualTo(70.0);
    assertThat(HealthMath.businessFromInvoiceGrowth(10, 0)).isEqualTo(100.0);
    assertThat(HealthMath.businessFromInvoiceGrowth(80, 100)).isEqualTo(30.0);
    assertThat(HealthMath.businessFromInvoiceGrowth(200, 100)).isEqualTo(100.0);
    assertThat(HealthMath.businessFromInvoiceGrowth(0, 10)).isEqualTo(0.0);

    assertThat(HealthMath.riskFactors(20, 0, 40, 40, 1, 8)).hasSize(4);
    assertThat(HealthMath.riskFactors(80, 70, 80, 80, 6, 8))
        .containsExactly("SaaS invoice due within grace period");
    assertThat(HealthMath.recommendedActions(20, 0, 40, 40)).hasSize(4);
    assertThat(HealthMath.recommendedActions(80, 70, 80, 80))
        .containsExactly("Send payment reminder before grace ends");
    assertThat(HealthMath.recommendedActions(80, 100, 80, 80))
        .containsExactly("Maintain regular CSM check-in");
  }

  @Test
  void savePlayTriggerAndHelpers() {
    assertThat(HealthMath.shouldTriggerSavePlay(null, 39.9)).isTrue();
    assertThat(HealthMath.shouldTriggerSavePlay(50.0, 39.0)).isTrue();
    assertThat(HealthMath.shouldTriggerSavePlay(30.0, 20.0)).isFalse();
    assertThat(HealthMath.shouldTriggerSavePlay(null, 40.0)).isFalse();
    assertThat(HealthBand.isAtRiskBand(HealthBand.AT_RISK)).isTrue();
    assertThat(HealthBand.isAtRiskBand(HealthBand.CHURNING)).isTrue();
    assertThat(HealthBand.isAtRiskBand(HealthBand.HEALTHY)).isFalse();
    assertThat(HealthBand.requireFilterBand("at_risk")).isEqualTo(HealthBand.AT_RISK);
    assertThat(HealthBand.requireFilterBand("CHURNING")).isEqualTo(HealthBand.CHURNING);
    assertThat(HealthBand.requireFilterBand("HEALTHY")).isNull();
    assertThat(HealthBand.requireFilterBand(" ")).isNull();
    assertThat(HealthBand.requireFilterBand(null)).isNull();
    assertThat(HealthMath.round2(1.005)).isEqualTo(1.01);
  }

  @Test
  void accountHealthScoreCopiesLists() {
    AccountHealthScore withNulls =
        new AccountHealthScore(
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            10,
            10,
            10,
            10,
            10,
            HealthBand.CHURNING,
            null,
            null,
            java.time.Instant.parse("2026-07-24T03:00:00Z"));
    assertThat(withNulls.riskFactors()).isEmpty();
    assertThat(withNulls.recommendedActions()).isEmpty();
  }

  @Test
  void savePlayActionType() {
    assertThat(SavePlayActionType.requireValid("call")).isEqualTo(SavePlayActionType.CALL);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> SavePlayActionType.requireValid("SMS"))
        .hasMessageContaining("invalid");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> SavePlayActionType.requireValid(null))
        .hasMessageContaining("required");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> SavePlayActionType.requireValid("  "))
        .hasMessageContaining("required");
  }
}
