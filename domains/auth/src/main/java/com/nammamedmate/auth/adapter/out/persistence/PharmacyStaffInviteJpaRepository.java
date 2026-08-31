package com.nammamedmate.auth.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PharmacyStaffInviteJpaRepository extends JpaRepository<PharmacyStaffInviteEntity, UUID> {

  @Query("SELECT e FROM PharmacyStaffInviteEntity e WHERE e.tokenHash = :hash AND e.usedAt IS NULL")
  Optional<PharmacyStaffInviteEntity> findActiveByTokenHash(@Param("hash") String hash);

  @Modifying(clearAutomatically = true)
  @Query("UPDATE PharmacyStaffInviteEntity e SET e.usedAt = :usedAt WHERE e.id = :id")
  int markUsed(@Param("id") UUID id, @Param("usedAt") Instant usedAt);
}
