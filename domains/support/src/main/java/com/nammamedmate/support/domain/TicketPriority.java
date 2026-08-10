package com.nammamedmate.support.domain;

import java.time.Duration;

public enum TicketPriority {
  LOW,
  MEDIUM,
  HIGH,
  URGENT;

  public SlaLevel defaultSlaLevel() {
    return switch (this) {
      case LOW -> SlaLevel.L1;
      case MEDIUM -> SlaLevel.L2;
      case HIGH -> SlaLevel.L3;
      case URGENT -> SlaLevel.L4;
    };
  }

  public Duration firstResponseSla() {
    return defaultSlaLevel().firstResponseWindow();
  }
}
