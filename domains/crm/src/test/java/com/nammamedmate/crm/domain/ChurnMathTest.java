package com.nammamedmate.crm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ChurnMathTest {

  @Test
  void logoChurnPctUsesTimes100() {
    // 8 / 606 ≈ 1.32
    assertThat(ChurnMath.logoChurnPct(8, 606)).isEqualByComparingTo(new BigDecimal("1.32"));
    assertThat(ChurnMath.logoChurnPct(0, 100)).isEqualByComparingTo(new BigDecimal("0.00"));
    assertThat(ChurnMath.logoChurnPct(5, 0)).isEqualByComparingTo(new BigDecimal("0.00"));
  }

  @Test
  void pctOfAndRiskLevels() {
    assertThat(ChurnMath.pctOf(12, 32)).isEqualByComparingTo(new BigDecimal("37.500"));
    assertThat(ChurnMath.pctOf(1, 0)).isEqualByComparingTo(new BigDecimal("0.000"));
    assertThat(RenewalRiskLevel.fromHealthScore(75)).isEqualTo(RenewalRiskLevel.LOW);
    assertThat(RenewalRiskLevel.fromHealthScore(50)).isEqualTo(RenewalRiskLevel.MEDIUM);
    assertThat(RenewalRiskLevel.fromHealthScore(49.9)).isEqualTo(RenewalRiskLevel.HIGH);
    assertThat(RenewalRiskLevel.requireFilter(null)).isNull();
    assertThat(RenewalRiskLevel.requireFilter("")).isNull();
    assertThat(RenewalRiskLevel.requireFilter("   ")).isNull();
    assertThat(RenewalRiskLevel.requireFilter(" high ")).isEqualTo(RenewalRiskLevel.HIGH);
    assertThat(RenewalRiskLevel.requireFilter("LOW")).isEqualTo(RenewalRiskLevel.LOW);
    assertThat(RenewalRiskLevel.requireFilter("MEDIUM")).isEqualTo(RenewalRiskLevel.MEDIUM);
    assertThatThrownBy(() -> RenewalRiskLevel.requireFilter("CRITICAL"))
        .isInstanceOf(com.nammamedmate.kernel.error.AppException.class)
        .extracting("code")
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void churnReasonValidation() {
    assertThat(ChurnReason.requireValid("price")).isEqualTo(ChurnReason.PRICE);
    assertThat(ChurnReason.all()).contains(ChurnReason.NOT_USING);
    assertThatThrownBy(() -> ChurnReason.requireValid(null))
        .extracting("code")
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> ChurnReason.requireValid("  "))
        .extracting("code")
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> ChurnReason.requireValid("CHEAP"))
        .extracting("code")
        .isEqualTo("VALIDATION_ERROR");
  }
}
