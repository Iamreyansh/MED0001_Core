package com.nammamedmate.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "admin_staff")
public class AdminStaffEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 100)
  private String name;

  @Column(name = "email", nullable = false, length = 255)
  private String email;

  @Column(name = "password_hash", length = 60)
  private String passwordHash;

  @Column(name = "role", nullable = false, length = 30)
  private String role;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "mfa_enabled", nullable = false)
  private boolean mfaEnabled;

  @Column(name = "totp_secret")
  private String totpSecret;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "backup_codes", columnDefinition = "jsonb")
  private List<Map<String, Object>> backupCodes;

  @Column(name = "failed_login_attempts", nullable = false)
  private short failedLoginAttempts;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "last_failed_at")
  private Instant lastFailedAt;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "last_active_at")
  private Instant lastActiveAt;

  @Column(name = "invited_by")
  private UUID invitedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected AdminStaffEntity() {}

  public AdminStaffEntity(
      UUID id,
      String name,
      String email,
      String passwordHash,
      String role,
      String status,
      boolean mfaEnabled,
      String totpSecret,
      List<Map<String, Object>> backupCodes,
      short failedLoginAttempts,
      Instant lockedUntil,
      Instant lastFailedAt,
      Instant lastLoginAt,
      Instant lastActiveAt,
      UUID invitedBy,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.name = name;
    this.email = email;
    this.passwordHash = passwordHash;
    this.role = role;
    this.status = status;
    this.mfaEnabled = mfaEnabled;
    this.totpSecret = totpSecret;
    this.backupCodes = backupCodes;
    this.failedLoginAttempts = failedLoginAttempts;
    this.lockedUntil = lockedUntil;
    this.lastFailedAt = lastFailedAt;
    this.lastLoginAt = lastLoginAt;
    this.lastActiveAt = lastActiveAt;
    this.invitedBy = invitedBy;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getRole() {
    return role;
  }

  public String getStatus() {
    return status;
  }

  public boolean isMfaEnabled() {
    return mfaEnabled;
  }

  public String getTotpSecret() {
    return totpSecret;
  }

  public List<Map<String, Object>> getBackupCodes() {
    return backupCodes == null ? null : List.copyOf(backupCodes);
  }

  public short getFailedLoginAttempts() {
    return failedLoginAttempts;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  public Instant getLastFailedAt() {
    return lastFailedAt;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }

  public Instant getLastActiveAt() {
    return lastActiveAt;
  }

  public UUID getInvitedBy() {
    return invitedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }
}
