package com.nammamedmate.pharmacy.domain;

/** Formats call duration as human-readable text (e.g. 5m 42s). */
public final class CallDurationFormatter {

  private CallDurationFormatter() {}

  public static String format(int durationSeconds) {
    if (durationSeconds < 60) {
      return durationSeconds + "s";
    }
    int minutes = durationSeconds / 60;
    int seconds = durationSeconds % 60;
    if (seconds == 0) {
      return minutes + "m";
    }
    return minutes + "m " + seconds + "s";
  }
}
