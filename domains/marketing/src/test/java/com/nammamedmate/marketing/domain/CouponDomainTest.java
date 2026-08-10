package com.nammamedmate.marketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CouponDomainTest {

  @Test
  void discountCapAndApiValue() {
    Coupon pct =
        new Coupon(
            UUID.randomUUID(),
            "NAMMA25",
            CouponType.PERCENTAGE,
            25,
            null,
            0,
            10_000L,
            100,
            0,
            0,
            null,
            1,
            List.of(),
            false,
            false,
            Instant.now(),
            Instant.now().plusSeconds(60),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            Instant.now(),
            Instant.now());
    assertThat(CouponDiscount.discountPaise(pct, 58_000L)).isEqualTo(10_000L);
    assertThat(pct.apiValue()).isEqualTo(25);
    assertThat(CouponDiscount.discountPaise(null, 1000)).isZero();
    assertThat(CouponDiscount.discountPaise(pct, 0)).isZero();

    Coupon flat =
        new Coupon(
            UUID.randomUUID(),
            "FLAT50",
            CouponType.FLAT_RS,
            null,
            5_000L,
            0,
            null,
            100,
            0,
            0,
            null,
            1,
            List.of(),
            false,
            false,
            Instant.now(),
            Instant.now().plusSeconds(60),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            Instant.now(),
            Instant.now());
    assertThat(CouponDiscount.discountPaise(flat, 1_000L)).isEqualTo(1_000L);
    Coupon flatNull =
        new Coupon(
            UUID.randomUUID(),
            "FLAT0",
            CouponType.FLAT_RS,
            null,
            null,
            0,
            null,
            100,
            0,
            0,
            null,
            1,
            List.of(),
            false,
            false,
            Instant.now(),
            Instant.now().plusSeconds(60),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            Instant.now(),
            Instant.now());
    assertThat(CouponDiscount.discountPaise(flatNull, 1_000L)).isZero();
    assertThat(flat.apiValue()).isEqualTo(MoneyFormats.paiseToRupees(5_000));

    Coupon free =
        new Coupon(
            UUID.randomUUID(),
            "FREEDEL",
            CouponType.FREE_DELIVERY,
            null,
            0L,
            0,
            null,
            100,
            0,
            0,
            null,
            1,
            List.of(UUID.randomUUID()),
            false,
            false,
            Instant.now(),
            Instant.now().plusSeconds(60),
            CouponStatus.ACTIVE,
            null,
            null,
            null,
            Instant.now(),
            Instant.now());
    assertThat(CouponDiscount.discountPaise(free, 10_000L)).isZero();
    assertThat(free.apiValue()).isEqualTo(0);
    assertThat(free.openToAllSegments()).isFalse();
  }
}
