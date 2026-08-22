package com.nammamedmate.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyMathTest {

  @Test
  void gstInclusiveExtractionMatchesStoryExample() {
    // ₹45 @ 12% → gst ≈ 4.82
    long gst = MoneyMath.gstFromInclusive(4500L, 12);
    assertThat(gst).isEqualTo(482L);
    assertThat(MoneyMath.taxableFromInclusive(4500L, 12)).isEqualTo(4500L - 482L);
    assertThat(MoneyMath.gstFromInclusive(0, 12)).isZero();
    assertThat(MoneyMath.gstFromInclusive(100, 0)).isZero();
  }

  @Test
  void discountCapsAndConversions() {
    assertThat(MoneyMath.maxDiscountPaise(100_000L)).isEqualTo(30_000L); // 30% < 500
    assertThat(MoneyMath.maxDiscountPaise(2_000_000L)).isEqualTo(50_000L); // ₹500 cap
    assertThat(MoneyMath.computeDiscountAmountPaise("PERCENTAGE", BigDecimal.valueOf(10), 100_000L))
        .isEqualTo(10_000L);
    assertThat(MoneyMath.computeDiscountAmountPaise("FLAT_RS", BigDecimal.valueOf(50), 100_000L))
        .isEqualTo(5_000L);
    assertThat(MoneyMath.computeDiscountAmountPaise(null, BigDecimal.TEN, 100)).isZero();
    assertThat(MoneyMath.computeDiscountAmountPaise("OTHER", BigDecimal.TEN, 100)).isZero();
    assertThat(MoneyMath.computeOfferDiscountPaise(DiscountType.PERCENTAGE, 10, 100_000L))
        .isEqualTo(10_000L);
    assertThat(MoneyMath.computeOfferDiscountPaise(DiscountType.FLAT_RS, 5_000L, 100_000L))
        .isEqualTo(5_000L);
    assertThat(MoneyMath.computeOfferDiscountPaise(null, 10, 100)).isZero();
    assertThat(MoneyMath.computeOfferDiscountPaise(DiscountType.PERCENTAGE, 10, 0)).isZero();
    assertThat(MoneyMath.computeOfferDiscountPaise(DiscountType.FLAT_RS, 0, 100)).isZero();
    assertThat(MoneyMath.computeOfferDiscountPaise(DiscountType.PERCENTAGE, -1, 100)).isZero();
    assertThat(MoneyMath.offerDiscountValueForApi(DiscountType.FLAT_RS, 5_000L))
        .isEqualByComparingTo("50.00");
    assertThat(MoneyMath.offerDiscountValueForApi(DiscountType.PERCENTAGE, 10))
        .isEqualByComparingTo("10");
    assertThat(MoneyMath.paiseToRupees(4500L)).isEqualByComparingTo("45.00");
    assertThat(MoneyMath.rupeesToPaise(new BigDecimal("45.00"))).isEqualTo(4500L);
    assertThatThrownBy(() -> MoneyMath.rupeesToPaise(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(MoneyMath.gstAfterDiscount(1200L, 10_000L, 2_000L)).isEqualTo(960L);
    assertThat(MoneyMath.gstAfterDiscount(1200L, 10_000L, 0L)).isEqualTo(1200L);
    assertThat(MoneyMath.gstAfterDiscount(0L, 10_000L, 100L)).isZero();
    assertThat(MoneyMath.gstAfterDiscount(1200L, 0L, 100L)).isEqualTo(1200L);
  }
}
