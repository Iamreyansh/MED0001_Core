package com.nammamedmate.analytics.domain;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Next run times for report schedules (06:00 Asia/Kolkata). */
public final class ReportScheduleTimes {

  public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  public static final LocalTime RUN_AT = LocalTime.of(6, 0);

  private ReportScheduleTimes() {}

  public static Instant nextRun(String cadence, Instant after, Clock clock) {
    Instant base = after == null ? clock.instant() : after;
    ZonedDateTime ist = base.atZone(IST);
    return switch (cadence == null ? "" : cadence.toUpperCase()) {
      case "DAILY" -> nextDaily(ist);
      case "WEEKLY" -> nextWeeklyMonday(ist);
      case "MONTHLY" -> nextMonthlyFirst(ist);
      default -> throw new IllegalArgumentException("INVALID_CADENCE");
    };
  }

  private static Instant nextDaily(ZonedDateTime ist) {
    ZonedDateTime candidate = ist.toLocalDate().atTime(RUN_AT).atZone(IST);
    if (!candidate.isAfter(ist)) {
      candidate = candidate.plusDays(1);
    }
    return candidate.toInstant();
  }

  private static Instant nextWeeklyMonday(ZonedDateTime ist) {
    LocalDate date = ist.toLocalDate();
    int daysUntilMon = (DayOfWeek.MONDAY.getValue() - date.getDayOfWeek().getValue() + 7) % 7;
    LocalDate monday = date.plusDays(daysUntilMon == 0 ? 0 : daysUntilMon);
    ZonedDateTime candidate = monday.atTime(RUN_AT).atZone(IST);
    if (!candidate.isAfter(ist)) {
      candidate = candidate.plusWeeks(1);
    }
    return candidate.toInstant();
  }

  private static Instant nextMonthlyFirst(ZonedDateTime ist) {
    LocalDate first = ist.toLocalDate().withDayOfMonth(1);
    ZonedDateTime candidate = first.atTime(RUN_AT).atZone(IST);
    if (!candidate.isAfter(ist)) {
      candidate = first.plusMonths(1).atTime(RUN_AT).atZone(IST);
    }
    return candidate.toInstant();
  }
}
