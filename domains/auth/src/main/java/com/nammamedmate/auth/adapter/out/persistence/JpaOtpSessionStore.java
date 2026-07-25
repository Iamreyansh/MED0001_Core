package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.OtpSessionRecord;
import com.nammamedmate.auth.application.port.out.OtpSessionStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JpaOtpSessionStore implements OtpSessionStore {

  private final OtpSessionJpaRepository repository;

  public JpaOtpSessionStore(OtpSessionJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public OtpSessionRecord save(OtpSessionRecord session) {
    OtpSessionEntity entity =
        new OtpSessionEntity(
            session.id(),
            session.phone(),
            session.otpHash(),
            (short) session.attempts(),
            session.deviceInfoJson(),
            session.expiresAt(),
            session.verifiedAt(),
            session.lockedAt(),
            session.createdAt());
    repository.save(entity);
    return session;
  }

  @Override
  public Optional<OtpSessionRecord> findById(UUID id) {
    return repository.findById(id).map(JpaOtpSessionStore::toRecord);
  }

  static OtpSessionRecord toRecord(OtpSessionEntity entity) {
    return new OtpSessionRecord(
        entity.getId(),
        entity.getPhone(),
        entity.getOtpHash(),
        entity.getAttempts(),
        entity.getDeviceInfoJson(),
        entity.getExpiresAt(),
        entity.getVerifiedAt(),
        entity.getLockedAt(),
        entity.getCreatedAt());
  }
}
