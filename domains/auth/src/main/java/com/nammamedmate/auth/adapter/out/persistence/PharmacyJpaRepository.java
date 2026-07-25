package com.nammamedmate.auth.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PharmacyJpaRepository extends JpaRepository<PharmacyEntity, UUID> {

  Optional<PharmacyEntity> findByIdAndDeletedAtIsNull(UUID id);
}
