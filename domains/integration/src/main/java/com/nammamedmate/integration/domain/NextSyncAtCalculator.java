package com.nammamedmate.integration.domain;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Computes next auto-sync at 02:00 IST (DAILY) or next Monday 02:00 IST (WEEKLY). */
public final class NextSyncAtCalculator {

  public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final LocalTime SYNC_TIME = LocalTime.of(2, 0);

  private NextSyncAtCalculator() {}

  public static Instant next(String frequency, Clock clock) {
    ZonedDateTime nowIst = Instant.now(clock).atZone(IST);
    if (AccountingSyncFrequencies.WEEKLY.equals(frequency)) {
      return nextWeekly(nowIst).toInstant();
    }
    return nextDaily(nowIst).toInstant();
  }

  private static ZonedDateTime nextDaily(ZonedDateTime nowIst) {
    ZonedDateTime candidate = nowIst.toLocalDate().atTime(SYNC_TIME).atZone(IST);
    if (!nowIst.isBefore(candidate)) {
      candidate = candidate.plusDays(1);
    }
    return candidate;
  }

  private static ZonedDateTime nextWeekly(ZonedDateTime nowIst) {
    LocalDate date = nowIst.toLocalDate();
    int daysUntilMonday = (DayOfWeek.MONDAY.getValue() - date.getDayOfWeek().getValue() + 7) % 7;
    LocalDate monday = date.plusDays(daysUntilMonday);
    ZonedDateTime candidate = monday.atTime(SYNC_TIME).atZone(IST);
    if (!nowIst.isBefore(candidate)) {
      candidate = candidate.plusWeeks(1);
    }
    return candidate;
  }
}
