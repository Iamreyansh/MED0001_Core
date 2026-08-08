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

  Optional<PrescriptionRef> findVerified(UUID prescriptionId, UUID customerId);

  Optional<PrescriptionDetail> findForBroadcast(UUID prescriptionId, UUID customerId);
}
