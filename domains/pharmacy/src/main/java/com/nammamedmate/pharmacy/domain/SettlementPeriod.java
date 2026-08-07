package com.nammamedmate.pharmacy.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** Weekly Mon–Sun settlement windows in IST calendar dates. */
public final class SettlementPeriod {

  private SettlementPeriod() {}

  public static LocalDate weekMonday(LocalDate date) {
    return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  public static LocalDate weekSunday(LocalDate date) {
    return weekMonday(date).plusDays(6);
  }

  public static LocalDate previousWeekMonday(LocalDate date) {
    return weekMonday(date).minusWeeks(1);
  }

  public static LocalDate previousWeekSunday(LocalDate date) {
    return previousWeekMonday(date).plusDays(6);
  }

  public static String label(LocalDate start, LocalDate end) {
    return start + " to " + end;
  }
}
