package com.nammamedmate.prescription.application.port.out;

import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import java.util.List;
import java.util.UUID;

/** Synchronous Schedule H1/X drug register write on pharmacy dispense. */
public interface ScheduleRegisterWritePort {

  void recordDispense(UUID pharmacyId, UUID rxId, UUID staffId, List<ApprovedMedicine> medicines);
}
