package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.PharmacyStaffPasswordResetRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffPasswordResetStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JpaPharmacyStaffPasswordResetStore implements PharmacyStaffPasswordResetStore {

  private final PharmacyStaffPasswordResetJpaRepository repository;

  public JpaPharmacyStaffPasswordResetStore(PharmacyStaffPasswordResetJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public void insert(PharmacyStaffPasswordResetRecord reset) {
    repository.save(
        new PharmacyStaffPasswordResetEntity(
            reset.id(),
            reset.staffId(),
            reset.tokenHash(),
            reset.expiresAt(),
            reset.usedAt(),
            reset.createdAt()));
  }

  @Override
  public Optional<PharmacyStaffPasswordResetRecord> findActiveByTokenHash(String tokenHash) {
    return repository
        .findActiveByTokenHash(tokenHash)
        .map(JpaPharmacyStaffPasswordResetStore::toRecord);
  }

  @Override
  public void markUsed(UUID id, Instant usedAt) {
    repository.markUsed(id, usedAt);
  }

  static PharmacyStaffPasswordResetRecord toRecord(PharmacyStaffPasswordResetEntity e) {
    return new PharmacyStaffPasswordResetRecord(
        e.getId(),
        e.getStaffId(),
        e.getTokenHash(),
        e.getExpiresAt(),
        e.getUsedAt(),
        e.getCreatedAt());
  }
}
