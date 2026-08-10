package com.nammamedmate.marketing.application;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponBudgetDigestSchedulerTest {

  @Mock CouponService coupons;

  @Test
  void delegates() {
    new CouponBudgetDigestScheduler(coupons).sendDigest();
    verify(coupons).sendDailyBudgetBurnDigest();
  }
}
