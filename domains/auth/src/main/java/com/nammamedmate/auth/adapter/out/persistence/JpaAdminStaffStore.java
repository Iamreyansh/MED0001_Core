package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import com.nammamedmate.auth.application.port.out.AdminStaffStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JpaAdminStaffStore implements AdminStaffStore {

  private final AdminStaffJpaRepository repository;

  public JpaAdminStaffStore(AdminStaffJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<AdminStaffRecord> findByEmail(String email) {
    return repository.findByEmailAndDeletedAtIsNull(email).map(JpaAdminStaffStore::toRecord);
  }

  @Override
  public Optional<AdminStaffRecord> findById(UUID id) {
    return repository.findByIdAndDeletedAtIsNull(id).map(JpaAdminStaffStore::toRecord);
  }

  @Override
  public AdminStaffRecord save(AdminStaffRecord staff) {
    Instant now = Instant.now();
    AdminStaffEntity entity =
        new AdminStaffEntity(
            staff.id(),
            staff.name(),
            staff.email(),
            staff.passwordHash(),
            staff.role(),
            staff.status(),
            staff.mfaEnabled(),
            staff.encryptedTotpSecret(),
            staff.backupCodes().isEmpty() ? null : List.copyOf(staff.backupCodes()),
            (short) staff.failedLoginAttempts(),
            staff.lockedUntil(),
            staff.lastFailedAt(),
            staff.lastLoginAt(),
            staff.lastActiveAt(),
            staff.invitedBy(),
            staff.createdAt() != null ? staff.createdAt() : now,
            now);
    repository.save(entity);
    return staff;
  }

  static AdminStaffRecord toRecord(AdminStaffEntity e) {
    return new AdminStaffRecord(
        e.getId(),
        e.getName(),
        e.getEmail(),
        e.getPasswordHash(),
        e.getRole(),
        e.getStatus(),
        e.isMfaEnabled(),
        e.getTotpSecret(),
        e.getBackupCodes() == null ? List.of() : e.getBackupCodes(),
        e.getFailedLoginAttempts(),
        e.getLockedUntil(),
        e.getLastFailedAt(),
        e.getLastLoginAt(),
        e.getLastActiveAt(),
        e.getInvitedBy(),
        e.getCreatedAt(),
        e.getUpdatedAt());
  }
}
