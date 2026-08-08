package com.nammamedmate.rider.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/** BR-004: weekly payout cycle Monday 00:00 IST → Sunday 23:59 IST. */
public final class PayoutCycle {

  public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  public record Window(LocalDate from, LocalDate to) {}

  private PayoutCycle() {}

  /** Current cycle containing {@code now} (Mon–Sun IST). */
  public static Window current(Instant now) {
    LocalDate today = now.atZone(IST).toLocalDate();
    LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return new Window(monday, monday.plusDays(6));
  }

  /** Previous completed Mon–Sun cycle relative to {@code now}. */
  public static Window previous(Instant now) {
    Window cur = current(now);
    return new Window(cur.from().minusWeeks(1), cur.to().minusWeeks(1));
  }

  /** Next payout date (Monday after cycle_to). */
  public static LocalDate nextPayoutDate(Window cycle) {
    return cycle.to().plusDays(1);
  }

  public static Instant startUtc(LocalDate cycleFrom) {
    return cycleFrom.atStartOfDay(IST).toInstant();
  }

  public static Instant endExclusiveUtc(LocalDate cycleTo) {
    return cycleTo.plusDays(1).atStartOfDay(IST).toInstant();
  }

  public static LocalDate istDate(Instant instant) {
    return instant.atZone(IST).toLocalDate();
  }

  public static boolean isMondayMorningWindow(Instant now) {
    ZonedDateTime z = now.atZone(IST);
    return z.getDayOfWeek() == DayOfWeek.MONDAY && z.getHour() < 12;
  }
}
