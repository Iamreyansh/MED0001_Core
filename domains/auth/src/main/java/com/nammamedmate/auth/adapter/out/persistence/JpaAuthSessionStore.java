package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaAuthSessionStore implements AuthSessionStore {

  private final AuthSessionJpaRepository repository;

  public JpaAuthSessionStore(AuthSessionJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public AuthSessionRecord save(AuthSessionRecord session) {
    repository.save(toEntity(session));
    return session;
  }

  @Override
  public Optional<AuthSessionRecord> findByRefreshTokenHash(String refreshTokenHash) {
    return repository.findByRefreshTokenHash(refreshTokenHash).map(JpaAuthSessionStore::toRecord);
  }

  @Override
  public Optional<AuthSessionRecord> findById(UUID id) {
    return repository.findById(id).map(JpaAuthSessionStore::toRecord);
  }

  @Override
  @Transactional
  public int markRotatedIfActive(UUID id, Instant rotatedAt) {
    return repository.markRotatedIfActive(id, rotatedAt);
  }

  @Override
  @Transactional
  public int revokeIfActive(UUID id, Instant revokedAt) {
    return repository.revokeIfActive(id, revokedAt);
  }

  @Override
  @Transactional
  public int revokeAllForUser(UUID userId, Instant revokedAt) {
    return repository.revokeAllForUser(userId, revokedAt);
  }

  @Override
  public List<AuthSessionRecord> listActiveByUserId(UUID userId, Instant now, int page, int limit) {
    int pageIndex = Math.max(page, 1) - 1;
    int pageSize = Math.max(limit, 1);
    return repository.listActive(userId, now, PageRequest.of(pageIndex, pageSize)).stream()
        .map(JpaAuthSessionStore::toRecord)
        .toList();
  }

  @Override
  public long countActiveByUserId(UUID userId, Instant now) {
    return repository.countActive(userId, now);
  }

  static AuthSessionEntity toEntity(AuthSessionRecord session) {
    return new AuthSessionEntity(
        session.id(),
        session.userId(),
        session.userType(),
        session.refreshTokenHash(),
        session.tokenScope(),
        session.deviceInfoJson(),
        session.ipAddress(),
        session.userAgent(),
        session.createdAt(),
        session.lastActiveAt(),
        session.expiresAt(),
        session.pharmacyId(),
        session.country(),
        session.city(),
        session.rotatedAt(),
        session.revokedAt());
  }

  static AuthSessionRecord toRecord(AuthSessionEntity entity) {
    return new AuthSessionRecord(
        entity.getId(),
        entity.getUserId(),
        entity.getUserType(),
        entity.getRefreshTokenHash(),
        entity.getTokenScope(),
        entity.getDeviceInfoJson(),
        entity.getIpAddress(),
        entity.getUserAgent(),
        entity.getCreatedAt(),
        entity.getLastActiveAt(),
        entity.getExpiresAt(),
        entity.getPharmacyId(),
        entity.getCountry(),
        entity.getCity(),
        entity.getRotatedAt(),
        entity.getRevokedAt());
  }
}
