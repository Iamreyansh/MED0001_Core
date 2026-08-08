package com.nammamedmate.rider.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Auto-assign composite score: distance 40%, rating 30%, load 20%, on_time 10% (AC-001 / BR-002).
 */
public final class AssignmentScoring {

  public static final int MAX_CONCURRENT = 2;
  public static final double MAX_DISTANCE_KM = 10.0;

  private AssignmentScoring() {}

  public static BigDecimal composite(
      double distanceKm, BigDecimal avgRating, int concurrentActive, BigDecimal onTimePct) {
    double distScore = Math.max(0.0, 1.0 - (distanceKm / MAX_DISTANCE_KM)) * 100.0;
    double rating =
        avgRating == null
            ? 50.0
            : avgRating.min(BigDecimal.valueOf(5)).max(BigDecimal.ZERO).doubleValue() / 5.0 * 100.0;
    int load = Math.max(0, Math.min(MAX_CONCURRENT, concurrentActive));
    double loadScore = ((MAX_CONCURRENT - load) / (double) MAX_CONCURRENT) * 100.0;
    double onTime =
        onTimePct == null
            ? 50.0
            : onTimePct.min(BigDecimal.valueOf(100)).max(BigDecimal.ZERO).doubleValue();
    double raw = distScore * 0.40 + rating * 0.30 + loadScore * 0.20 + onTime * 0.10;
    return BigDecimal.valueOf(raw).setScale(2, RoundingMode.HALF_UP);
  }
}
