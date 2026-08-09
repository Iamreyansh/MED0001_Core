package com.nammamedmate.crm.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;
import java.util.Set;

public final class InvoiceLineItemType {

  public static final String PLAN = "PLAN";
  public static final String ADDON = "ADDON";
  public static final String CREDIT = "CREDIT";

  private static final Set<String> ALL = Set.of(PLAN, ADDON, CREDIT);

  private InvoiceLineItemType() {}

  public static String requireValid(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "item_type required", 400);
    }
    String v = raw.trim().toUpperCase(Locale.ROOT);
    if (!ALL.contains(v)) {
      throw new AppException("VALIDATION_ERROR", "invalid item_type", 400);
    }
    return v;
  }
}
