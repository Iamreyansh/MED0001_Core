package com.nammamedmate.crm.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;

public final class LostReason {

  public static final String PRICE = "PRICE";
  public static final String COMPETITOR = "COMPETITOR";
  public static final String NOT_INTERESTED = "NOT_INTERESTED";
  public static final String TIMELINE = "TIMELINE";
  public static final String OTHER = "OTHER";

  private LostReason() {}

  public static String requireValid(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "lost_reason required", 400);
    }
    String s = raw.trim().toUpperCase(Locale.ROOT);
    return switch (s) {
      case PRICE, COMPETITOR, NOT_INTERESTED, TIMELINE, OTHER -> s;
      default -> throw new AppException("VALIDATION_ERROR", "invalid lost_reason", 400);
    };
  }
}
