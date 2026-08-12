package com.nammamedmate.automation.domain;

import java.time.Instant;
import java.util.UUID;

public record RuleHealthMetrics(
    UUID ruleId,
    String name,
    String status,
    long fireCount24h,
    long executedCount24h,
    long exceptionCount24h,
    String lastError,
    Instant lastErrorAt,
    Integer avgExecutionMs,
    Instant lastFiredAt) {

  public double successRatePct() {
    long denom = executedCount24h + exceptionCount24h;
    if (denom <= 0) {
      return 0.0;
    }
    return Math.round(executedCount24h * 1000.0 / denom) / 10.0;
  }
}
