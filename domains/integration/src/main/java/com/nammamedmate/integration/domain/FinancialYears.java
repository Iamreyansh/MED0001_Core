package com.nammamedmate.integration.domain;

import java.time.LocalDate;

/** Indian financial year labels (Apr–Mar), e.g. {@code 2026-27}. */
public final class FinancialYears {

  private FinancialYears() {}

  public static String of(LocalDate date) {
    if (date == null) {
      throw new IllegalArgumentException("date required");
    }
    int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
    int endTwo = (startYear + 1) % 100;
    return startYear + "-" + String.format("%02d", endTwo);
  }
}
