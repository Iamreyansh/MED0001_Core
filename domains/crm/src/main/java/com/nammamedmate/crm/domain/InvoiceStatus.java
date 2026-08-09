package com.nammamedmate.crm.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;
import java.util.Set;

public final class InvoiceStatus {

  public static final String PAID = "PAID";
  public static final String DUE = "DUE";
  public static final String OVERDUE = "OVERDUE";
  public static final String DUNNING = "DUNNING";
  public static final String WAIVED = "WAIVED";

  private static final Set<String> ALL = Set.of(PAID, DUE, OVERDUE, DUNNING, WAIVED);

  private InvoiceStatus() {}

  public static String requireValid(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "status required", 400);
    }
    String v = raw.trim().toUpperCase(Locale.ROOT);
    if (!ALL.contains(v)) {
      throw new AppException("VALIDATION_ERROR", "invalid invoice status", 400);
    }
    return v;
  }

  public static boolean isClosed(String status) {
    return PAID.equals(status) || WAIVED.equals(status);
  }

  public static boolean countsAsOverdue(String status) {
    return OVERDUE.equals(status) || DUNNING.equals(status);
  }
}
