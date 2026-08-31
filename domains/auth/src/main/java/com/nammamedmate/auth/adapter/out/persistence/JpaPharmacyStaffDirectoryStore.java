package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.adapter.out.persistence.PharmacyAssignmentJpaRepository.StaffDirectoryProjection;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffDirectoryRow;
import com.nammamedmate.auth.application.port.out.PharmacyStaffDirectoryStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JpaPharmacyStaffDirectoryStore implements PharmacyStaffDirectoryStore {

  private final PharmacyAssignmentJpaRepository repository;

  public JpaPharmacyStaffDirectoryStore(PharmacyAssignmentJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public void insertAssignment(
      UUID id, UUID staffId, UUID pharmacyId, UUID roleId, Instant joinedAt) {
    repository.save(
        new PharmacyAssignmentEntity(id, staffId, pharmacyId, roleId, true, joinedAt, null));
  }

  @Override
  public void reactivateAssignment(UUID staffId, UUID pharmacyId, UUID roleId) {
    repository.reactivate(staffId, pharmacyId, roleId);
  }

  @Override
  public int deactivateAssignment(UUID staffId, UUID pharmacyId, Instant removedAt) {
    return repository.deactivate(staffId, pharmacyId, removedAt);
  }

  @Override
  public Optional<PharmacyAssignmentRecord> findAssignment(UUID staffId, UUID pharmacyId) {
    return repository
        .findByStaffIdAndPharmacyId(staffId, pharmacyId)
        .map(JpaPharmacyAssignmentStore::toRecord);
  }

  @Override
  public List<PharmacyStaffDirectoryRow> listDirectory(UUID pharmacyId) {
    return repository.listDirectory(pharmacyId).stream()
        .map(JpaPharmacyStaffDirectoryStore::toRow)
        .toList();
  }

  static PharmacyStaffDirectoryRow toRow(StaffDirectoryProjection p) {
    return new PharmacyStaffDirectoryRow(
        p.getStaff_id(),
        p.getName(),
        p.getEmail(),
        p.getPhone(),
        p.getStatus(),
        p.getRole_code(),
        p.getIs_active(),
        p.getJoined_at(),
        p.getPos_pin_set());
  }
}
