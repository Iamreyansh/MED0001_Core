package com.nammamedmate.auth.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PharmacyStaffJpaRepository extends JpaRepository<PharmacyStaffEntity, UUID> {

  Optional<PharmacyStaffEntity> findByEmailAndDeletedAtIsNull(String email);

  Optional<PharmacyStaffEntity> findByPhoneAndDeletedAtIsNull(String phone);

  Optional<PharmacyStaffEntity> findByIdAndDeletedAtIsNull(UUID id);
}
