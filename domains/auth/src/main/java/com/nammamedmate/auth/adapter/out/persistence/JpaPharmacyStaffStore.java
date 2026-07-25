package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JpaPharmacyStaffStore implements PharmacyStaffStore {

  private final PharmacyStaffJpaRepository repository;

  public JpaPharmacyStaffStore(PharmacyStaffJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<PharmacyStaffRecord> findByEmail(String email) {
    return repository.findByEmailAndDeletedAtIsNull(email).map(JpaPharmacyStaffStore::toRecord);
  }

  @Override
  public Optional<PharmacyStaffRecord> findByPhone(String phone) {
    return repository.findByPhoneAndDeletedAtIsNull(phone).map(JpaPharmacyStaffStore::toRecord);
  }

  @Override
  public Optional<PharmacyStaffRecord> findById(UUID id) {
    return repository.findByIdAndDeletedAtIsNull(id).map(JpaPharmacyStaffStore::toRecord);
  }

  @Override
  public PharmacyStaffRecord save(PharmacyStaffRecord staff) {
    Instant now = Instant.now();
    PharmacyStaffEntity entity =
        new PharmacyStaffEntity(
            staff.id(),
            staff.name(),
            staff.email(),
            staff.phone(),
            staff.passwordHash(),
            staff.posPinHash(),
            staff.status(),
            (short) staff.failedLoginAttempts(),
            staff.lockedUntil(),
            staff.lastFailedAt(),
            staff.lastLoginAt(),
            staff.invitedBy(),
            staff.createdAt() != null ? staff.createdAt() : now,
            now);
    repository.save(entity);
    return staff;
  }

  static PharmacyStaffRecord toRecord(PharmacyStaffEntity e) {
    return new PharmacyStaffRecord(
        e.getId(),
        e.getName(),
        e.getEmail(),
        e.getPhone(),
        e.getPasswordHash(),
        e.getPosPinHash(),
        e.getStatus(),
        e.getFailedLoginAttempts(),
        e.getLockedUntil(),
        e.getLastFailedAt(),
        e.getLastLoginAt(),
        e.getInvitedBy(),
        e.getCreatedAt(),
        e.getUpdatedAt());
  }
}
