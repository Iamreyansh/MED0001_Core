package com.nammamedmate.automation.domain;

import java.time.Duration;
import java.time.Instant;

public record CircuitBreakerState(
    String actionType,
    int thresholdPerHour,
    CircuitStatus status,
    int firesLastHour,
    Instant openedAt,
    Instant resetAt,
    Instant updatedAt) {

  public static final int DEFAULT_THRESHOLD = 50;
  public static final int DEFAULT_RESET_MINUTES = 30;

  public CircuitBreakerState {
    if (thresholdPerHour <= 0) {
      thresholdPerHour = DEFAULT_THRESHOLD;
    }
    status = status == null ? CircuitStatus.CLOSED : status;
    firesLastHour = Math.max(0, firesLastHour);
  }

  public CircuitBreakerState maybeAutoReset(Instant now) {
    if (status != CircuitStatus.OPEN || resetAt == null || now == null || now.isBefore(resetAt)) {
      return this;
    }
    return new CircuitBreakerState(
        actionType, thresholdPerHour, CircuitStatus.CLOSED, 0, null, null, now);
  }

  public boolean shouldOpen(int fires) {
    return fires >= thresholdPerHour;
  }

  public static int parseResetMinutes(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_RESET_MINUTES;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      return parsed <= 0 ? DEFAULT_RESET_MINUTES : parsed;
    } catch (NumberFormatException ex) {
      return DEFAULT_RESET_MINUTES;
    }
  }

  public CircuitBreakerState open(Instant now, int fires, int resetMinutes) {
    int mins = resetMinutes <= 0 ? DEFAULT_RESET_MINUTES : resetMinutes;
    Instant opened = now == null ? Instant.EPOCH : now;
    return new CircuitBreakerState(
        actionType,
        thresholdPerHour,
        CircuitStatus.OPEN,
        fires,
        opened,
        opened.plus(Duration.ofMinutes(mins)),
        opened);
  }

  public CircuitBreakerState withFires(int fires, Instant now) {
    return new CircuitBreakerState(
        actionType, thresholdPerHour, status, fires, openedAt, resetAt, now);
  }
}
