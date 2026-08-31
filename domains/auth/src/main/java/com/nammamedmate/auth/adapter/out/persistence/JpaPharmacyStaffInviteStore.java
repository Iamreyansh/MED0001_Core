package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.PharmacyStaffInviteRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffInviteStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JpaPharmacyStaffInviteStore implements PharmacyStaffInviteStore {

  private final PharmacyStaffInviteJpaRepository repository;

  public JpaPharmacyStaffInviteStore(PharmacyStaffInviteJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public void insert(PharmacyStaffInviteRecord invite) {
    repository.save(
        new PharmacyStaffInviteEntity(
            invite.id(),
            invite.staffId(),
            invite.pharmacyId(),
            invite.tokenHash(),
            invite.expiresAt(),
            invite.usedAt(),
            invite.createdAt()));
  }

  @Override
  public Optional<PharmacyStaffInviteRecord> findActiveByTokenHash(String tokenHash) {
    return repository.findActiveByTokenHash(tokenHash).map(JpaPharmacyStaffInviteStore::toRecord);
  }

  @Override
  public void markUsed(UUID id, Instant usedAt) {
    repository.markUsed(id, usedAt);
  }

  static PharmacyStaffInviteRecord toRecord(PharmacyStaffInviteEntity e) {
    return new PharmacyStaffInviteRecord(
        e.getId(),
        e.getStaffId(),
        e.getPharmacyId(),
        e.getTokenHash(),
        e.getExpiresAt(),
        e.getUsedAt(),
        e.getCreatedAt());
  }
}
