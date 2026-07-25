package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.AdminAuthEventRecord;
import com.nammamedmate.auth.application.port.out.AdminAuthEventStore;
import org.springframework.stereotype.Component;

@Component
public class JpaAdminAuthEventStore implements AdminAuthEventStore {

  private final AdminAuthEventJpaRepository repository;

  public JpaAdminAuthEventStore(AdminAuthEventJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public void save(AdminAuthEventRecord event) {
    repository.save(
        new AdminAuthEventEntity(
            event.id(),
            event.adminId(),
            event.eventType(),
            event.ipAddress() == null || event.ipAddress().isBlank()
                ? "0.0.0.0"
                : event.ipAddress(),
            event.userAgent(),
            event.metadata(),
            event.createdAt()));
  }
}
