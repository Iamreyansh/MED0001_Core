package com.nammamedmate.crm.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;

/** Renewal pipeline risk from health score (EPIC-014 STORY-007). */
public final class RenewalRiskLevel {

  public static final String LOW = "LOW";
  public static final String MEDIUM = "MEDIUM";
  public static final String HIGH = "HIGH";

  private RenewalRiskLevel() {}

  /** ≥75 LOW; 50–74 MEDIUM; &lt;50 HIGH. */
  public static String fromHealthScore(double healthScore) {
    if (healthScore >= 75.0) {
      return LOW;
    }
    if (healthScore >= 50.0) {
      return MEDIUM;
    }
    return HIGH;
  }

  public static String requireFilter(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String v = raw.trim().toUpperCase(Locale.ROOT);
    if (!LOW.equals(v) && !MEDIUM.equals(v) && !HIGH.equals(v)) {
      throw new AppException("VALIDATION_ERROR", "risk_level must be LOW, MEDIUM, or HIGH", 400);
    }
    return v;
  }
}
