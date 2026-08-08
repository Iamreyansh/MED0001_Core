package com.nammamedmate.settings.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminStaffStore {

  record AdminStaffRow(
      UUID id,
      String name,
      String email,
      String role,
      String status,
      boolean mfaEnabled,
      Instant lastActiveAt,
      UUID invitedBy,
      Instant inviteExpiresAt,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {}

  record InviterRef(UUID id, String name) {}

  record AuditTrailEntry(String action, String from, String to, String by, Instant at) {}

  record PageResult(List<AdminStaffRow> items, long total) {
    public PageResult {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  Optional<AdminStaffRow> findById(UUID id);

  Optional<AdminStaffRow> findByEmail(String email);

  Optional<InviterRef> findInviter(UUID id);

  boolean emailExists(String email);

  long countActiveSuperAdmins();

  PageResult list(String role, String status, String search, int page, int limit);

  void insertInvited(
      UUID id,
      String name,
      String email,
      String role,
      UUID invitedBy,
      String inviteTokenHash,
      Instant inviteExpiresAt,
      Instant now);

  /** Refresh invite token/expiry/role/name for an existing INVITED row (BR-9 re-send). */
  void refreshInvite(
      UUID id,
      String name,
      String role,
      UUID invitedBy,
      String inviteTokenHash,
      Instant inviteExpiresAt,
      Instant updatedAt);

  void update(UUID id, String name, String role, String status, Instant updatedAt);

  void softDelete(UUID id, Instant deletedAt);

  void setResetToken(UUID id, String resetTokenHash, Instant expiresAt, Instant updatedAt);

  List<AuditTrailEntry> listAuditTrail(UUID staffId);
}
