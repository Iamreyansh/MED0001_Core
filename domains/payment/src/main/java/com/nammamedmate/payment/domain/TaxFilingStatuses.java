package com.nammamedmate.payment.domain;

/** Tax filing lifecycle for EPIC-012 STORY-007. */
public final class TaxFilingStatuses {

  public static final String PENDING = "PENDING";
  public static final String FILED = "FILED";
  public static final String OVERDUE = "OVERDUE";

  private TaxFilingStatuses() {}

  /** Overlay PENDING → OVERDUE when past due (AC-005). */
  public static String displayStatus(
      String stored, java.time.LocalDate dueDate, java.time.LocalDate today) {
    if (FILED.equals(stored)) {
      return FILED;
    }
    if (dueDate != null && today != null && today.isAfter(dueDate)) {
      return OVERDUE;
    }
    if (OVERDUE.equals(stored)) {
      return OVERDUE;
    }
    return PENDING;
  }
}
