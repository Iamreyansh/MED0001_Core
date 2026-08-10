package com.nammamedmate.prescription.application.port.out;

import java.util.UUID;

/** Applied when pharmacy queue / audit create runs — before pharmacist review when possible. */
public interface DoctorAutoFlagPort {

  /** Ensures compliance audit is FLAGGED for blacklist / unrecognised qualification when linked. */
  void applyPendingFlags(UUID rxId, UUID pharmacyId);
}
