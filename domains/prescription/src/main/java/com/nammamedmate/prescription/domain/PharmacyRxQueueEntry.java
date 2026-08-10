package com.nammamedmate.prescription.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PharmacyRxQueueEntry(
    UUID id,
    UUID rxId,
    UUID pharmacyId,
    UUID orderId,
    Instant receivedAt,
    String status,
    List<ApprovedMedicine> approvedMedicines,
    UUID approvedBy,
    Instant approvedAt,
    String rejectedReason,
    String rejectedCustomMessage,
    UUID rejectedBy,
    Instant rejectedAt,
    UUID dispensedBy,
    Instant dispensedAt,
    String notes,
    boolean duplicateWarning,
    Instant overdueNotifiedAt,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public static final Duration SLA = Duration.ofHours(2);

  public record ApprovedMedicine(String name, int quantity, BigDecimal price, String schedule) {
    public ApprovedMedicine(String name, int quantity, BigDecimal price) {
      this(name, quantity, price, null);
    }
  }

  public PharmacyRxQueueEntry {
    approvedMedicines = approvedMedicines == null ? null : List.copyOf(approvedMedicines);
  }

  public Instant slaDeadline() {
    return receivedAt.plus(SLA);
  }

  public boolean isOverdue(Instant now) {
    return "PENDING_REVIEW".equals(status) && now.isAfter(slaDeadline());
  }

  public long overdueByMinutes(Instant now) {
    if (!isOverdue(now)) {
      return 0L;
    }
    return Duration.between(slaDeadline(), now).toMinutes();
  }
}
