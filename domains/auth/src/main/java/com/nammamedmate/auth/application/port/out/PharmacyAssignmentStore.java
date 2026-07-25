package com.nammamedmate.auth.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyAssignmentStore {

  /** All active assignments for a staff member, ordered by joined_at ASC. */
  List<PharmacyAssignmentRecord> listActiveByStaffId(UUID staffId);

  /** Single active assignment for a staff+pharmacy pair. */
  Optional<PharmacyAssignmentRecord> findActive(UUID staffId, UUID pharmacyId);
}
