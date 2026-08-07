package com.nammamedmate.pharmacy.adapter.out.metrics;

import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ponytail: zeros/empty until EPIC-008 order metrics wire up. */
public final class StubPharmacyOrderMetricsClient implements PharmacyOrderMetricsPort {

  private static final BigDecimal ZERO = new BigDecimal("0.00");
  private static final BigDecimal ZERO_PREP = new BigDecimal("0.0");

  @Override
  public Performance performance(UUID pharmacyId) {
    return new Performance(ZERO, ZERO, ZERO, ZERO, 0, 0, 0L);
  }

  @Override
  public CommissionLedger commissionLedger(UUID pharmacyId) {
    return new CommissionLedger(0L, 0L, 0L, 0L, null, null);
  }

  @Override
  public List<RecentOrder> recentOrders(UUID pharmacyId, int limit) {
    return List.of();
  }

  @Override
  public PeriodMetrics periodMetrics(UUID pharmacyId, LocalDate periodEnd, int days) {
    return new PeriodMetrics(0, 0, 0, ZERO, ZERO, ZERO, ZERO, ZERO_PREP, 0, ZERO, 0, 0L, (short) 0);
  }

  @Override
  public RatingListResult listRatings(
      UUID pharmacyId, Integer ratingFilter, String sort, String order, int limit, int offset) {
    return new RatingListResult(ZERO, 0, Map.of(5, 0, 4, 0, 3, 0, 2, 0, 1, 0), List.of(), 0L);
  }

  @Override
  public OrderListResult listOrders(
      UUID pharmacyId, String status, LocalDate fromDate, LocalDate toDate, int limit, int offset) {
    return new OrderListResult(List.of(), 0L);
  }

  @Override
  public long annualGmvYtdPaise(UUID pharmacyId) {
    return 0L;
  }

  @Override
  public long gmvForPeriodPaise(UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
    return 0L;
  }
}
