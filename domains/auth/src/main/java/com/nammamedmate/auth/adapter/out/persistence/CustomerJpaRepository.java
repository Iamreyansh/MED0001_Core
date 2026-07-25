package com.nammamedmate.auth.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerJpaRepository extends JpaRepository<CustomerEntity, UUID> {

  Optional<CustomerEntity> findByPhoneAndDeletedAtIsNull(String phone);
}
