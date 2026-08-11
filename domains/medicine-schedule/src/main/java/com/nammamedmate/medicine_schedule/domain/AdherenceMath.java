package com.nammamedmate.medicine_schedule.domain;

/** Adherence percentage and day-status helpers computed from DoseLog counts. */
public final class AdherenceMath {

  private AdherenceMath() {}

  /**
   * {@code adherence_pct = (taken / scheduled) * 100}.
   *
   * <p>ponytail: story wrote -100; treat as ×100
   */
  public static Double pct(int taken, int scheduled) {
    if (scheduled <= 0) {
      return null;
    }
    return Math.round((taken * 10000.0) / scheduled) / 100.0;
  }

  public static DayAdherenceStatus dayStatus(int taken, int scheduled) {
    if (scheduled <= 0) {
      return DayAdherenceStatus.NO_DOSES;
    }
    if (taken <= 0) {
      return DayAdherenceStatus.MISSED;
    }
    if (taken >= scheduled) {
      return DayAdherenceStatus.PERFECT;
    }
    return DayAdherenceStatus.PARTIAL;
  }
}
