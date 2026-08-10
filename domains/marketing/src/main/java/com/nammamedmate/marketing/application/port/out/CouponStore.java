package com.nammamedmate.marketing.application.port.out;

import com.nammamedmate.marketing.domain.Coupon;
import com.nammamedmate.marketing.domain.CouponStatus;
import com.nammamedmate.marketing.domain.CouponType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CouponStore {

  record RedemptionRow(
      UUID id,
      UUID customerId,
      String customerName,
      UUID orderId,
      long discountAppliedPaise,
      Instant redeemedAt) {}

  record DailyRedemption(LocalDate date, int count) {}

  record Economics(long discountSpendPaise, long revenueAttributedPaise) {}

  record BudgetBurnRow(String code, long budgetTotalPaise, long budgetUsedPaise) {}

  record Chips(
      int activeCount, long totalRedemptions, long discountSpendPaise, long marketingSpendPaise) {}

  Coupon insert(Coupon coupon);

  Optional<Coupon> findByCode(String code);

  Optional<Coupon> findById(UUID id);

  List<Coupon> list(
      CouponStatus status, CouponType type, String sort, String order, int offset, int limit);

  long count(CouponStatus status, CouponType type);

  Chips chips();

  void update(Coupon coupon);

  void hardDelete(UUID id);

  boolean isSegmentReferencedByActiveCoupon(UUID segmentId);

  int countRedemptionsForCustomer(UUID couponId, UUID customerId);

  void insertRedemption(
      UUID id,
      UUID couponId,
      UUID orderId,
      UUID customerId,
      long discountAppliedPaise,
      long orderTotalPaise,
      Instant redeemedAt);

  List<RedemptionRow> listRedemptions(UUID couponId, int offset, int limit);

  long countRedemptions(UUID couponId);

  List<DailyRedemption> dailyRedemptions(UUID couponId, int limit);

  Economics economics(UUID couponId);

  /** Coupons with budget_used/budget_total > 0.7 that had redemptions on the given IST day. */
  List<BudgetBurnRow> highBurnCouponsForDay(LocalDate istDay);
}
