package com.nammamedmate.auth.adapter.out.persistence;

import com.nammamedmate.auth.application.port.out.CustomerRecord;
import com.nammamedmate.auth.application.port.out.CustomerStore;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JpaCustomerStore implements CustomerStore {

  private final CustomerJpaRepository repository;

  public JpaCustomerStore(CustomerJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<CustomerRecord> findByPhone(String phone) {
    return repository.findByPhoneAndDeletedAtIsNull(phone).map(JpaCustomerStore::toRecord);
  }

  @Override
  public Optional<CustomerRecord> findById(UUID id) {
    return repository.findByIdAndDeletedAtIsNull(id).map(JpaCustomerStore::toRecord);
  }

  @Override
  public CustomerRecord save(CustomerRecord customer) {
    Instant now = Instant.now();
    String[] tokens = customer.deviceTokens().toArray(String[]::new);
    CustomerEntity entity =
        new CustomerEntity(
            customer.id(),
            customer.phone(),
            tokens,
            customer.name(),
            customer.avatarUrl(),
            customer.dateOfBirth(),
            customer.gender(),
            customer.preferredLanguage(),
            customer.segment(),
            customer.walletBalancePaise(),
            customer.loyaltyPoints(),
            customer.createdAt(),
            now,
            null);
    repository.save(entity);
    return customer;
  }

  static CustomerRecord toRecord(CustomerEntity entity) {
    List<String> tokens =
        entity.getDeviceTokens() == null ? List.of() : Arrays.asList(entity.getDeviceTokens());
    return new CustomerRecord(
        entity.getId(),
        entity.getPhone(),
        tokens,
        entity.getName(),
        entity.getAvatarUrl(),
        entity.getDateOfBirth(),
        entity.getGender(),
        entity.getPreferredLanguage(),
        entity.getSegment(),
        entity.getWalletBalancePaise(),
        entity.getLoyaltyPoints(),
        entity.getCreatedAt());
  }
}
