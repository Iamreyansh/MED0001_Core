package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.PrescriptionPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ponytail: EPIC-008 not done — UUID stub with redacted meds list for broadcast. */
public class StubPrescriptionAdapter implements PrescriptionPort {

  /** Well-known id that behaves as expired for tests. */
  public static final UUID EXPIRED_ID = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

  /** Well-known id that is not found. */
  public static final UUID NOT_FOUND_ID = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");

  private static final List<MedicineLine> DEFAULT_MEDS =
      List.of(new MedicineLine("Metformin 500mg", 60), new MedicineLine("Glipizide 5mg", 30));

  @Override
  public Optional<PrescriptionRef> findVerified(UUID prescriptionId, UUID customerId) {
    if (prescriptionId == null || NOT_FOUND_ID.equals(prescriptionId)) {
      return Optional.empty();
    }
    if (EXPIRED_ID.equals(prescriptionId)) {
      return Optional.empty();
    }
    return Optional.of(new PrescriptionRef(prescriptionId, "VERIFIED"));
  }

  @Override
  public Optional<PrescriptionDetail> findForBroadcast(UUID prescriptionId, UUID customerId) {
    if (prescriptionId == null || NOT_FOUND_ID.equals(prescriptionId)) {
      return Optional.empty();
    }
    if (EXPIRED_ID.equals(prescriptionId)) {
      return Optional.of(new PrescriptionDetail(prescriptionId, "EXPIRED", true, DEFAULT_MEDS));
    }
    return Optional.of(new PrescriptionDetail(prescriptionId, "VERIFIED", false, DEFAULT_MEDS));
  }
}
