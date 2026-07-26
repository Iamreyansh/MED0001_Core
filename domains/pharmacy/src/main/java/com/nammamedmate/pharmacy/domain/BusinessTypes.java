package com.nammamedmate.pharmacy.domain;

import java.util.Locale;
import java.util.Set;

public final class BusinessTypes {

  public static final Set<String> ALL = Set.of("PHARMACY", "HOSPITAL", "CLINIC_PHARMACY");

  private BusinessTypes() {}

  public static String requireValid(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("MISSING_REQUIRED_FIELD");
    }
    String value = raw.trim().toUpperCase(Locale.ROOT);
    if (!ALL.contains(value)) {
      throw new IllegalArgumentException("INVALID_BUSINESS_TYPE");
    }
    return value;
  }
}
