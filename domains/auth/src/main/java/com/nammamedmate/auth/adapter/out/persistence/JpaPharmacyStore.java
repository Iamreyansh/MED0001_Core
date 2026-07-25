package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JpaPharmacyStore implements PharmacyStore {

  private final PharmacyJpaRepository repository;

  public JpaPharmacyStore(PharmacyJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<PharmacyRecord> findById(UUID id) {
    return repository
        .findByIdAndDeletedAtIsNull(id)
        .map(
            e ->
                new PharmacyRecord(
                    e.getId(), e.getName(), e.getLogoUrl(), e.getCity(), e.getSubscriptionPlan()));
  }
}
