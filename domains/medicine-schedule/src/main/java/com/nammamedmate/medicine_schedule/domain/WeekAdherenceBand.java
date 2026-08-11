package com.nammamedmate.medicine_schedule.domain;

public enum WeekAdherenceBand {
  HIGH,
  MEDIUM,
  LOW;

  /** HIGH ≥85, MEDIUM 60–84, LOW &lt;60. Null pct → LOW. */
  public static WeekAdherenceBand fromPct(Double pct) {
    if (pct == null || pct < 60.0) {
      return LOW;
    }
    if (pct >= 85.0) {
      return HIGH;
    }
    return MEDIUM;
  }
}
