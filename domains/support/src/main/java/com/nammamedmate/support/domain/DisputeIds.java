package com.nammamedmate.support.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Human-readable dispute ids: DSP-YYYYMMDD-XXXXXX. */
public final class DisputeIds {

  private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

  private DisputeIds() {}

  public static LocalDate dayKey(Instant instant) {
    return LocalDate.ofInstant(instant, ZoneOffset.UTC);
  }

  public static String format(LocalDate day, int seq) {
    if (seq < 1 || seq > 999_999) {
      throw new IllegalArgumentException("seq out of range: " + seq);
    }
    return "DSP-" + DAY.format(day) + "-" + String.format("%06d", seq);
  }
}
