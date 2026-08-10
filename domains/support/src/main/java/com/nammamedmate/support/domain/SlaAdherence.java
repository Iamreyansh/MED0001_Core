package com.nammamedmate.support.domain;

/** Locked formula: (within_sla / total_resolved) × 100. Story “−100” is a typo. */
public final class SlaAdherence {

  private SlaAdherence() {}

  public static double pct(long withinSla, long totalResolved) {
    if (totalResolved <= 0) {
      return 0.0;
    }
    return (withinSla * 100.0) / totalResolved;
  }
}
