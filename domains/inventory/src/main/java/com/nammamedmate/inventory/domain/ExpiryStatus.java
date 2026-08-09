package com.nammamedmate.inventory.domain;

import java.time.LocalDate;

public final class ExpiryStatus {

  private ExpiryStatus() {}

  public static String of(LocalDate expiryDate, LocalDate today) {
    if (expiryDate.isBefore(today)) {
      return "EXPIRED";
    }
    if (!expiryDate.isAfter(today.plusMonths(4))) {
      return "EXPIRING_SOON";
    }
    return "OK";
  }

  public static long daysToExpiry(LocalDate expiryDate, LocalDate today) {
    return java.time.temporal.ChronoUnit.DAYS.between(today, expiryDate);
  }

  /** Bucket for alert grouping: null if beyond 4 months or already expired. */
  public static String alertBucket(LocalDate expiryDate, LocalDate today) {
    if (expiryDate.isBefore(today)) {
      return null;
    }
    LocalDate under1 = today.plusMonths(1);
    LocalDate under2 = today.plusMonths(2);
    LocalDate under4 = today.plusMonths(4);
    if (!expiryDate.isAfter(under1)) {
      return "UNDER_1_MONTH";
    }
    if (!expiryDate.isAfter(under2)) {
      return "1_TO_2_MONTHS";
    }
    if (!expiryDate.isAfter(under4)) {
      return "2_TO_4_MONTHS";
    }
    return null;
  }
}
