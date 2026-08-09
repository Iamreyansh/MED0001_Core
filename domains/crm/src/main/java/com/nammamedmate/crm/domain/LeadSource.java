package com.nammamedmate.crm.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;

public final class LeadSource {

  public static final String ORGANIC = "ORGANIC";
  public static final String REFERRAL = "REFERRAL";
  public static final String AD = "AD";
  public static final String PARTNER = "PARTNER";
  public static final String MARKETPLACE = "MARKETPLACE";

  private LeadSource() {}

  public static String requireValid(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "source required", 400);
    }
    String s = raw.trim().toUpperCase(Locale.ROOT);
    return switch (s) {
      case ORGANIC, REFERRAL, AD, PARTNER, MARKETPLACE -> s;
      default -> throw new AppException("VALIDATION_ERROR", "invalid source", 400);
    };
  }
}
