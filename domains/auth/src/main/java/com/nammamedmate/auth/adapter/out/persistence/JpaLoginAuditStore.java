package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.LoginAuditRecord;
import com.nammamedmate.auth.application.port.out.LoginAuditStore;
import org.springframework.stereotype.Component;

@Component
public class JpaLoginAuditStore implements LoginAuditStore {

  private final LoginAuditJpaRepository repository;

  public JpaLoginAuditStore(LoginAuditJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public void save(LoginAuditRecord audit) {
    repository.save(
        new LoginAuditEntity(
            audit.id(),
            audit.actorType(),
            audit.identifier(),
            audit.staffId(),
            audit.success(),
            audit.failureReason(),
            audit.ipAddress(),
            audit.userAgent(),
            audit.createdAt()));
  }
}
