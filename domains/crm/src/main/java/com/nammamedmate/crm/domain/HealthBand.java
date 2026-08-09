package com.nammamedmate.crm.domain;

import java.util.Locale;

/** Health bands for account health scoring (EPIC-014 STORY-005). */
public final class HealthBand {

  public static final String HEALTHY = "HEALTHY";
  public static final String MODERATE = "MODERATE";
  public static final String AT_RISK = "AT_RISK";
  public static final String CHURNING = "CHURNING";

  /** Score threshold for at-risk list and MRR-at-risk (AT_RISK + CHURNING). */
  public static final double AT_RISK_THRESHOLD = 50.0;

  /** First drop below this auto-triggers a save-play notification. */
  public static final double SAVE_PLAY_TRIGGER = 40.0;

  private HealthBand() {}

  public static String fromScore(double overallScore) {
    if (overallScore >= 75.0) {
      return HEALTHY;
    }
    if (overallScore >= 50.0) {
      return MODERATE;
    }
    if (overallScore >= 25.0) {
      return AT_RISK;
    }
    return CHURNING;
  }

  public static boolean isAtRiskBand(String band) {
    return AT_RISK.equals(band) || CHURNING.equals(band);
  }

  public static String requireFilterBand(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String v = raw.trim().toUpperCase(Locale.ROOT);
    if (!AT_RISK.equals(v) && !CHURNING.equals(v)) {
      return null;
    }
    return v;
  }
}
