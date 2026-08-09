package com.nammamedmate.crm.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;
import java.util.Set;

/** Structured churn survey reasons (EPIC-014 STORY-007). */
public final class ChurnReason {

  public static final String PRICE = "PRICE";
  public static final String FEATURES = "FEATURES";
  public static final String MOVING_TO_COMPETITOR = "MOVING_TO_COMPETITOR";
  public static final String CLOSING_BUSINESS = "CLOSING_BUSINESS";
  public static final String NOT_USING = "NOT_USING";
  public static final String OTHER = "OTHER";

  private static final Set<String> ALL =
      Set.of(PRICE, FEATURES, MOVING_TO_COMPETITOR, CLOSING_BUSINESS, NOT_USING, OTHER);

  private ChurnReason() {}

  public static String requireValid(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 422);
    }
    String v = raw.trim().toUpperCase(Locale.ROOT);
    if (!ALL.contains(v)) {
      throw new AppException("VALIDATION_ERROR", "invalid churn reason", 422);
    }
    return v;
  }

  public static Set<String> all() {
    return ALL;
  }
}
