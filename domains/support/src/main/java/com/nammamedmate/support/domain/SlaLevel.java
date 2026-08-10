package com.nammamedmate.support.domain;

import java.time.Duration;

public enum SlaLevel {
  L1,
  L2,
  L3,
  L4;

  public Duration firstResponseWindow() {
    return switch (this) {
      case L1 -> Duration.ofMinutes(30);
      case L2 -> Duration.ofHours(2);
      case L3 -> Duration.ofHours(8);
      case L4 -> Duration.ofHours(24);
    };
  }

  public SlaLevel next() {
    return switch (this) {
      case L1 -> L2;
      case L2 -> L3;
      case L3 -> L4;
      case L4 -> L4;
    };
  }
}
