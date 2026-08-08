package com.nammamedmate.order.domain;

import java.util.UUID;

/**
 * Weighted pharmacy ranking for smart-select.
 *
 * <p>score = 0.60*distance + 0.20*fill_rate + 0.10*rating + 0.10*eta (all components in [0,1]).
 */
public final class PharmacyScorer {

  public static final double WEIGHT_DISTANCE = 0.60;
  public static final double WEIGHT_FILL_RATE = 0.20;
  public static final double WEIGHT_RATING = 0.10;
  public static final double WEIGHT_ETA = 0.10;
  public static final double AVG_SPEED_KMH = 25.0;
  public static final double DEFAULT_AVG_PREP_MINUTES = 10.0;

  private PharmacyScorer() {}

  public static int deliveryEtaMinutes(double distanceKm, Double avgPrepMinutes) {
    double prep =
        avgPrepMinutes == null || avgPrepMinutes <= 0 ? DEFAULT_AVG_PREP_MINUTES : avgPrepMinutes;
    return (int) Math.round((distanceKm / AVG_SPEED_KMH) * 60.0 + prep);
  }

  public static PharmacyScore score(
      UUID pharmacyId,
      double distanceKm,
      double maxRadiusKm,
      double fillRate7d,
      double pharmacyRating,
      Double avgPrepMinutes) {
    double radius = maxRadiusKm <= 0 ? 1.0 : maxRadiusKm;
    double distanceScore = clamp01(1.0 - (distanceKm / radius));
    double fillRateScore = clamp01(fillRate7d / 100.0);
    double ratingScore = clamp01(pharmacyRating / 5.0);
    int eta = deliveryEtaMinutes(distanceKm, avgPrepMinutes);
    double etaScore = Math.max(0.0, 1.0 - (eta / 60.0));
    double total =
        WEIGHT_DISTANCE * distanceScore
            + WEIGHT_FILL_RATE * fillRateScore
            + WEIGHT_RATING * ratingScore
            + WEIGHT_ETA * etaScore;
    return new PharmacyScore(
        pharmacyId,
        distanceKm,
        distanceScore,
        fillRate7d,
        fillRateScore,
        pharmacyRating,
        ratingScore,
        eta,
        etaScore,
        total);
  }

  private static double clamp01(double v) {
    if (v < 0) {
      return 0;
    }
    if (v > 1) {
      return 1;
    }
    return v;
  }
}
