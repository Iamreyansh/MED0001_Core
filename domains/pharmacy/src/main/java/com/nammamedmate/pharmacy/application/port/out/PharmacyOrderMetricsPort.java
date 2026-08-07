package com.nammamedmate.pharmacy.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-domain order/performance metrics for admin pharmacy directory (EPIC-008 / STORY-004-002).
 * Implementations must not create a compile-time domain dependency.
 */
public interface PharmacyOrderMetricsPort {

  record Performance(
      BigDecimal fillRatePct,
      BigDecimal onTimePrepPct,
      BigDecimal cancelRatePct,
      BigDecimal avgRating,
      int reviewCount,
      int orders30d,
      long gmv30dPaise) {}

  record CommissionLedger(
      long gmvCurrentPeriodPaise,
      long commissionEarnedPaise,
      long tcsDeductedPaise,
      long netPayablePaise,
      LocalDate lastSettlementDate,
      LocalDate nextSettlementDate) {}

  record RecentOrder(
      UUID orderId, String orderNumber, String status, long amountPaise, Instant createdAt) {}

  record PeriodMetrics(
      int ordersReceived,
      int ordersFulfilled,
      int ordersCancelled,
      BigDecimal fillRatePct,
      BigDecimal onTimePrepPct,
      BigDecimal cancelRatePct,
      BigDecimal outOfStockRatePct,
      BigDecimal avgPrepMinutes,
      int complaintCount,
      BigDecimal avgRating,
      int reviewCount,
      long gmvPeriodPaise,
      short consecutiveLowFillDays) {}

  record PharmacyRating(
      UUID ratingId,
      UUID orderId,
      String orderNumber,
      String customerName,
      int rating,
      String reviewText,
      Instant createdAt) {}

  record RatingListResult(
      BigDecimal avgRating,
      int reviewCount,
      Map<Integer, Integer> distribution,
      List<PharmacyRating> ratings,
      long total) {
    public RatingListResult {
      distribution = distribution == null ? Map.of() : Map.copyOf(distribution);
      ratings = ratings == null ? List.of() : List.copyOf(ratings);
    }
  }

  record AdminOrderDetail(
      UUID orderId,
      String orderNumber,
      String status,
      String customerName,
      int itemCount,
      long totalAmountPaise,
      int prepMinutes,
      boolean prepOnTime,
      boolean hasRx,
      Instant createdAt,
      Instant deliveredAt) {}

  record OrderListResult(List<AdminOrderDetail> orders, long total) {
    public OrderListResult {
      orders = orders == null ? List.of() : List.copyOf(orders);
    }
  }

  Performance performance(UUID pharmacyId);

  CommissionLedger commissionLedger(UUID pharmacyId);

  /** Most recent orders for the pharmacy drawer (caller asks for at least 5). */
  List<RecentOrder> recentOrders(UUID pharmacyId, int limit);

  /** Trailing window ending on {@code periodEnd} (inclusive) for nightly aggregation. */
  PeriodMetrics periodMetrics(UUID pharmacyId, LocalDate periodEnd, int days);

  RatingListResult listRatings(
      UUID pharmacyId, Integer ratingFilter, String sort, String order, int limit, int offset);

  OrderListResult listOrders(
      UUID pharmacyId, String status, LocalDate fromDate, LocalDate toDate, int limit, int offset);

  /** Calendar-year GMV through today (IST date boundary handled by caller). */
  long annualGmvYtdPaise(UUID pharmacyId);

  long gmvForPeriodPaise(UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd);
}
