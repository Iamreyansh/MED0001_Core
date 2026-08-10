package com.nammamedmate.prescription.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record RxAuditEntry(
    UUID id,
    UUID rxId,
    UUID orderId,
    UUID pharmacyId,
    String schedule,
    String auditStatus,
    Instant auditDeadline,
    boolean possibleDuplicate,
    UUID possibleDuplicateRxId,
    UUID verifiedBy,
    Instant verifiedAt,
    String flagReason,
    String flagSeverity,
    UUID flaggedBy,
    Instant flaggedAt,
    String notes,
    Instant createdAt) {

  public static Duration deadlineFor(String schedule) {
    if ("H1".equals(schedule) || "X".equals(schedule)) {
      return Duration.ofHours(24);
    }
    if ("H".equals(schedule)) {
      return Duration.ofDays(7);
    }
    return Duration.ofHours(24);
  }

  public static int rank(String schedule) {
    return switch (schedule == null ? "" : schedule) {
      case "X" -> 3;
      case "H1" -> 2;
      case "H" -> 1;
      default -> 0;
    };
  }

  public static String higher(String a, String b) {
    return rank(a) >= rank(b) ? (a == null ? "NONE" : a) : b;
  }

  public boolean isOverdue(Instant now) {
    return "AWAITING_AUDIT".equals(auditStatus) && now.isAfter(auditDeadline);
  }

  public double hoursSinceDispense(Instant now) {
    if (createdAt == null) {
      return 0d;
    }
    long seconds = Math.max(0L, Duration.between(createdAt, now).getSeconds());
    return Math.round(seconds / 36d) / 100d;
  }
}
