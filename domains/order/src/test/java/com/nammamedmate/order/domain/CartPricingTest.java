package com.nammamedmate.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.order.domain.CartPricing.Bill;
import com.nammamedmate.order.domain.CartPricing.CouponResult;
import com.nammamedmate.order.domain.CartPricing.CouponType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CartPricingTest {

  @Test
  void namma25On25500AndDeliveryUsesPreCouponTotal() {
    // NAMMA25 on ₹255 → discount 63.75; delivery 0 because pre-coupon 255 >= 199
    // (story AC text said 25, but BR-3 / Notes: threshold uses pre-coupon item_total)
    Bill bill = CartPricing.compute(25_500L, "NAMMA25", 0L);
    assertThat(bill.itemTotalPaise()).isEqualTo(25_500L);
    assertThat(bill.couponDiscountPaise()).isEqualTo(6_375L);
    assertThat(bill.deliveryFeePaise()).isEqualTo(0L);
    assertThat(bill.handlingFeePaise()).isEqualTo(500L);
    assertThat(CartPricing.paiseToRupees(bill.couponDiscountPaise()))
        .isEqualByComparingTo(new BigDecimal("63.75"));

    // discounted subtotal would be < 199, but delivery still free on pre-coupon 210
    Bill preCouponWins = CartPricing.compute(21_000L, "NAMMA25", 0L);
    assertThat(preCouponWins.deliveryFeePaise()).isEqualTo(0L);
    assertThat(preCouponWins.subtotalAfterDiscountPaise()).isLessThan(19_900L);
  }

  @Test
  void freedelOverridesDeliveryBelowThreshold() {
    // AC: FREEDEL on ₹150 → delivery 0
    Bill bill = CartPricing.compute(15_000L, "FREEDEL", 0L);
    assertThat(bill.deliveryFeePaise()).isEqualTo(0L);
    assertThat(bill.handlingFeePaise()).isEqualTo(500L);
  }

  @Test
  void freeDeliveryWhenItemTotalAtOrAbove199() {
    Bill bill = CartPricing.compute(19_900L, null, 0L);
    assertThat(bill.deliveryFeePaise()).isEqualTo(0L);
    Bill below = CartPricing.compute(19_899L, null, 0L);
    assertThat(below.deliveryFeePaise()).isEqualTo(2_500L);
  }

  @Test
  void flat50MinNotMetAndHappyPath() {
    assertThatThrownBy(() -> CartPricing.applyCoupon("FLAT50", 35_000L))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("COUPON_MIN_NOT_MET");

    CouponResult ok = CartPricing.applyCoupon("flat50", 39_900L);
    assertThat(ok.type()).isEqualTo(CouponType.FLAT);
    assertThat(ok.discountPaise()).isEqualTo(5_000L);
  }

  @Test
  void invalidCouponAndDiscountCapAndWallet() {
    assertThatThrownBy(() -> CartPricing.applyCoupon("NOPE", 10_000L))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_COUPON");
    assertThatThrownBy(() -> CartPricing.applyCoupon(null, 10_000L))
        .isInstanceOf(AppException.class);

    // discount capped at item_total
    assertThat(CartPricing.couponDiscountPaise("FLAT50", 1_000L)).isEqualTo(1_000L);

    Bill withWallet = CartPricing.compute(10_000L, null, 50_000L);
    // 100 + 25 + 5 = 130 → wallet applies all
    assertThat(withWallet.walletAppliedPaise()).isEqualTo(13_000L);
    assertThat(withWallet.totalPayablePaise()).isEqualTo(0L);

    Bill empty = CartPricing.compute(0L, "NAMMA25", 100L);
    assertThat(empty.handlingFeePaise()).isEqualTo(0L);
    assertThat(empty.deliveryFeePaise()).isEqualTo(0L);
    assertThat(empty.couponDiscountPaise()).isEqualTo(0L);
  }

  @Test
  void namma25AndFreedelApplyMessages() {
    assertThat(CartPricing.applyCoupon("NAMMA25", 25_500L).message()).contains("25%");
    assertThat(CartPricing.applyCoupon("FREEDEL", 15_000L).type())
        .isEqualTo(CouponType.FREE_DELIVERY);
  }

  @Test
  void discountCapInComputeAndBlankCouponNormalize() {
    Bill bill = CartPricing.compute(1_000L, "FLAT50", 0L);
    assertThat(bill.couponDiscountPaise()).isEqualTo(1_000L);
    assertThat(CartPricing.normalize("  ")).isNull();
    assertThat(CartPricing.couponDiscountPaise("UNKNOWN", 10_000L)).isZero();
    assertThat(CartPricing.couponDiscountPaise("NAMMA25", 0L)).isZero();
    assertThat(CartPricing.compute(-5L, null, -1L).itemTotalPaise()).isZero();
  }

  @Test
  void zonePricedDeliveryOverrideAndFreedelForcesZero() {
    Bill bill = CartPricing.compute(10_000L, null, 0L, 4000L, 500L);
    assertThat(bill.deliveryFeePaise()).isEqualTo(4000L);
    assertThat(bill.handlingFeePaise()).isEqualTo(500L);
    Bill freedel = CartPricing.compute(10_000L, "FREEDEL", 0L, 4000L, 500L);
    assertThat(freedel.deliveryFeePaise()).isZero();
    assertThat(freedel.handlingFeePaise()).isEqualTo(500L);
    Bill empty = CartPricing.compute(0L, null, 0L, 4000L, 500L);
    assertThat(empty.deliveryFeePaise()).isZero();
    assertThat(empty.handlingFeePaise()).isZero();
  }
}
