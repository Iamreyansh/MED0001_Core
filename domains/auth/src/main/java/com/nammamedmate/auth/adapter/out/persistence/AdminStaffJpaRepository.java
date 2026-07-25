package com.nammamedmate.auth.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AdminStaffJpaRepository extends JpaRepository<AdminStaffEntity, UUID> {
  Optional<AdminStaffEntity> findByEmailAndDeletedAtIsNull(String email);

  Optional<AdminStaffEntity> findByIdAndDeletedAtIsNull(UUID id);
}
