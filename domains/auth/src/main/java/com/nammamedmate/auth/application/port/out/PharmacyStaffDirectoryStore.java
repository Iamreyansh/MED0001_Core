package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyStaffDirectoryStore {

  void insertAssignment(UUID id, UUID staffId, UUID pharmacyId, UUID roleId, Instant joinedAt);

  void reactivateAssignment(UUID staffId, UUID pharmacyId, UUID roleId);

  int deactivateAssignment(UUID staffId, UUID pharmacyId, Instant removedAt);

  Optional<PharmacyAssignmentRecord> findAssignment(UUID staffId, UUID pharmacyId);

  List<PharmacyStaffDirectoryRow> listDirectory(UUID pharmacyId);
}
