package com.nammamedmate.auth.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionJpaRepository extends JpaRepository<AuthSessionEntity, UUID> {

  Optional<AuthSessionEntity> findByRefreshTokenHash(String refreshTokenHash);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE AuthSessionEntity s SET s.rotatedAt = :at WHERE s.id = :id AND s.rotatedAt IS NULL"
          + " AND s.revokedAt IS NULL")
  int markRotatedIfActive(@Param("id") UUID id, @Param("at") Instant at);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE AuthSessionEntity s SET s.revokedAt = :at WHERE s.id = :id AND s.revokedAt IS NULL")
  int revokeIfActive(@Param("id") UUID id, @Param("at") Instant at);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE AuthSessionEntity s SET s.revokedAt = :at WHERE s.userId = :userId AND s.revokedAt IS"
          + " NULL")
  int revokeAllForUser(@Param("userId") UUID userId, @Param("at") Instant at);

  @Query(
      "SELECT s FROM AuthSessionEntity s WHERE s.userId = :userId AND s.revokedAt IS NULL AND"
          + " s.rotatedAt IS NULL AND s.expiresAt > :now ORDER BY s.lastActiveAt DESC")
  List<AuthSessionEntity> listActive(
      @Param("userId") UUID userId, @Param("now") Instant now, Pageable pageable);

  @Query(
      "SELECT COUNT(s) FROM AuthSessionEntity s WHERE s.userId = :userId AND s.revokedAt IS NULL"
          + " AND s.rotatedAt IS NULL AND s.expiresAt > :now")
  long countActive(@Param("userId") UUID userId, @Param("now") Instant now);
}
