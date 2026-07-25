package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import org.springframework.stereotype.Component;

@Component
public class JpaAuthSessionStore implements AuthSessionStore {

  private final AuthSessionJpaRepository repository;

  public JpaAuthSessionStore(AuthSessionJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public AuthSessionRecord save(AuthSessionRecord session) {
    AuthSessionEntity entity =
        new AuthSessionEntity(
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
            session.pharmacyId());
    repository.save(entity);
    return session;
  }
}
