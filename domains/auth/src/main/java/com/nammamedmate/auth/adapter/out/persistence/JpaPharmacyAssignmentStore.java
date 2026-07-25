package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.adapter.out.persistence.PharmacyAssignmentJpaRepository.AssignmentProjection;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JpaPharmacyAssignmentStore implements PharmacyAssignmentStore {

  private final PharmacyAssignmentJpaRepository repository;

  public JpaPharmacyAssignmentStore(PharmacyAssignmentJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<PharmacyAssignmentRecord> listActiveByStaffId(UUID staffId) {
    return repository.findActiveByStaffIdOrderByJoinedAt(staffId).stream()
        .map(JpaPharmacyAssignmentStore::toRecord)
        .toList();
  }

  @Override
  public Optional<PharmacyAssignmentRecord> findActive(UUID staffId, UUID pharmacyId) {
    return repository
        .findActiveByStaffIdAndPharmacyId(staffId, pharmacyId)
        .map(JpaPharmacyAssignmentStore::toRecord);
  }

  static PharmacyAssignmentRecord toRecord(AssignmentProjection p) {
    return new PharmacyAssignmentRecord(
        p.getId(),
        p.getStaff_id(),
        p.getPharmacy_id(),
        p.getRole_code(),
        p.getIs_active(),
        p.getJoined_at(),
        p.getRemoved_at(),
        p.getPharmacy_name());
  }
}
