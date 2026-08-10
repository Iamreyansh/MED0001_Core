package com.nammamedmate.prescription.application.port.out;

import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyRxQueueStore {

  void insert(PharmacyRxQueueEntry entry);

  Optional<PharmacyRxQueueEntry> findByRxAndPharmacy(UUID rxId, UUID pharmacyId);

  /** Latest queue row for an Rx (any pharmacy) — used for retroactive doctor blacklist flags. */
  Optional<PharmacyRxQueueEntry> findLatestByRxId(UUID rxId);

  record Page(List<PharmacyRxQueueEntry> items, long total) {
    public Page {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  Page list(
      UUID pharmacyId,
      String status,
      String source,
      String search,
      int page,
      int limit,
      String sort);

  record Kpis(
      int pendingReview,
      int pendingReviewOverdue,
      int awaitingDispense,
      int dispensedTodayCount,
      long dispensedTodayValuePaise,
      int avgTurnaroundMinutes,
      double digitalSharePct,
      double slaOnTimePct) {}

  Kpis computeKpis(UUID pharmacyId, Instant now);

  void markApproved(
      UUID id,
      List<ApprovedMedicine> medicines,
      UUID approvedBy,
      Instant approvedAt,
      String notes,
      boolean duplicateWarning,
      Instant updatedAt);

  void markRejected(
      UUID id,
      String reason,
      String customMessage,
      UUID rejectedBy,
      Instant rejectedAt,
      Instant updatedAt);

  void markDispensed(UUID id, UUID dispensedBy, Instant dispensedAt, Instant updatedAt);

  void markOverdueNotified(UUID id, Instant notifiedAt, Instant updatedAt);

  List<PharmacyRxQueueEntry> findPendingOverdueUnnotified(Instant deadline, int limit);

  boolean hasDuplicateDispense(
      UUID customerId, String medicineName, Instant since, UUID excludeRxId);
}
