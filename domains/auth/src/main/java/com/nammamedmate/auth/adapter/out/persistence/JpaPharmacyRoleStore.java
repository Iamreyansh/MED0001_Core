package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.PharmacyRoleRecord;
import com.nammamedmate.auth.application.port.out.PharmacyRoleStore;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JpaPharmacyRoleStore implements PharmacyRoleStore {

  private final PharmacyRoleJpaRepository repository;

  public JpaPharmacyRoleStore(PharmacyRoleJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<PharmacyRoleRecord> listSystemRoles() {
    return repository.findBySystemTrueAndDeletedAtIsNullOrderByCodeAsc().stream()
        .map(JpaPharmacyRoleStore::toRecord)
        .toList();
  }

  @Override
  public List<PharmacyRoleRecord> listCustomByPharmacy(UUID pharmacyId) {
    return repository.findByPharmacyIdAndDeletedAtIsNullOrderByCodeAsc(pharmacyId).stream()
        .map(JpaPharmacyRoleStore::toRecord)
        .toList();
  }

  @Override
  public Optional<PharmacyRoleRecord> findById(UUID id) {
    return repository.findByIdAndDeletedAtIsNull(id).map(JpaPharmacyRoleStore::toRecord);
  }

  @Override
  public Optional<PharmacyRoleRecord> findSystemByCode(String code) {
    return repository
        .findBySystemTrueAndCodeAndDeletedAtIsNull(code)
        .map(JpaPharmacyRoleStore::toRecord);
  }

  @Override
  public Optional<PharmacyRoleRecord> findActiveByPharmacyAndCode(UUID pharmacyId, String code) {
    return repository
        .findByPharmacyIdAndCodeAndDeletedAtIsNull(pharmacyId, code)
        .map(JpaPharmacyRoleStore::toRecord);
  }

  @Override
  public PharmacyRoleRecord save(PharmacyRoleRecord role) {
    PharmacyRoleEntity entity =
        new PharmacyRoleEntity(
            role.id(),
            role.pharmacyId(),
            role.code(),
            role.code(),
            role.displayName(),
            role.system(),
            role.permissions().toArray(String[]::new),
            role.createdBy(),
            role.createdAt(),
            role.updatedAt(),
            role.deletedAt());
    return toRecord(repository.save(entity));
  }

  @Override
  public int countActiveStaff(UUID roleId, UUID pharmacyId) {
    if (pharmacyId == null) {
      return repository.countActiveStaffGlobal(roleId);
    }
    return repository.countActiveStaff(roleId, pharmacyId);
  }

  static PharmacyRoleRecord toRecord(PharmacyRoleEntity e) {
    String[] perms = e.getPermissions() == null ? new String[0] : e.getPermissions();
    return new PharmacyRoleRecord(
        e.getId(),
        e.getPharmacyId(),
        e.getCode(),
        e.getDisplayName(),
        e.isSystem(),
        Arrays.asList(perms),
        e.getCreatedBy(),
        e.getCreatedAt(),
        e.getUpdatedAt(),
        e.getDeletedAt());
  }
}
