package com.nammamedmate.order.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Prescription attach / broadcast until EPIC-008 — stub accepts UUIDs as VERIFIED. */
public interface PrescriptionPort {

  record PrescriptionRef(UUID id, String status) {}

  record MedicineLine(String name, int quantity) {}

  /** Redacted Rx summary for quote broadcast (no file URL). */
  record PrescriptionDetail(UUID id, String status, boolean expired, List<MedicineLine> medicines) {

    public PrescriptionDetail {
      medicines = medicines == null ? List.of() : List.copyOf(medicines);
    }
  }

  default Optional<PrescriptionRef> findVerified(UUID prescriptionId, UUID customerId) {
    return Optional.empty();
  }

  default Optional<PrescriptionDetail> findForBroadcast(UUID prescriptionId, UUID customerId) {
    return Optional.empty();
  }

  /** Lands an Rx order on the pharmacy review queue (no-op until bridged). */
  default void enqueueForPharmacy(UUID prescriptionId, UUID pharmacyId, UUID orderId) {}

  /** Pharmacy Rx queue status for fulfilment (PENDING_REVIEW / APPROVED / VERIFIED). */
  default Optional<String> pharmacyQueueStatus(UUID prescriptionId, UUID pharmacyId) {
    return Optional.empty();
  }
}
