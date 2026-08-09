package com.nammamedmate.crm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CrmMoneyAndPlanNamesTest {

  @Test
  @DisplayName("AC-002 annual price = monthly × 10; savings 16.7%")
  void annualPricing() {
    assertThat(CrmMoney.annualPaise(69900L)).isEqualTo(699000L);
    assertThat(CrmMoney.paiseToRupees(CrmMoney.annualPaise(69900L)))
        .isEqualByComparingTo("6990.00");
    assertThat(CrmMoney.annualSavingsPct()).isEqualByComparingTo("16.7");
  }

  @Test
  @DisplayName("AC-009 attach_rate_pct = (with / total) × 100")
  void attachRate() {
    assertThat(CrmMoney.attachRatePct(34, 100)).isEqualByComparingTo("34.0");
    assertThat(CrmMoney.attachRatePct(0, 0)).isEqualByComparingTo("0.0");
  }

  @Test
  void proratedCreditMidCyclePositive() {
    long credit = CrmMoney.proratedCreditPaise(19900, 15, 31);
    assertThat(credit).isGreaterThan(0L);
    assertThat(CrmMoney.proratedCreditPaise(19900, 31, 31)).isZero();
    assertThat(CrmMoney.proratedCreditPaise(0, 10, 30)).isZero();
    assertThat(CrmMoney.proratedCreditPaise(100, 0, 30)).isZero();
    assertThat(CrmMoney.proratedCreditPaise(100, 10, 0)).isZero();
    assertThat(CrmMoney.proratedCreditPaise(100, 40, 30)).isZero();
  }

  @Test
  void rupeesToPaiseValidates() {
    assertThat(CrmMoney.rupeesToPaise(new BigDecimal("799.00"))).isEqualTo(79900L);
    assertThatThrownBy(() -> CrmMoney.rupeesToPaise(null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> CrmMoney.rupeesToPaise(new BigDecimal("1.999")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> CrmMoney.rupeesToPaise(new BigDecimal("-1")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void planTier() {
    assertThat(PlanNames.upgradePath(PlanNames.FREE)).isEqualTo(PlanNames.STARTER);
    assertThat(PlanNames.upgradePath(PlanNames.ENTERPRISE)).isNull();
    assertThat(PlanNames.upgradePath("NOPE")).isNull();
    assertThat(PlanNames.starterFeaturesEnabled(PlanNames.STARTER)).isTrue();
    assertThat(PlanNames.starterFeaturesEnabled(PlanNames.FREE)).isFalse();
    assertThat(PlanNames.growthFeaturesEnabled(PlanNames.RETAIL_PRO)).isTrue();
    assertThat(PlanNames.growthFeaturesEnabled(PlanNames.STARTER)).isFalse();
    assertThat(PlanNames.tierIndex("NOPE")).isEqualTo(-1);
  }

  @Test
  void moduleMatrixRowCopiesNullPlans() {
    ModuleMatrixRow row =
        new ModuleMatrixRow(java.util.UUID.randomUUID(), "mod_x", "X", "X", "CORE", null);
    assertThat(row.planNames()).isEmpty();
  }
}
