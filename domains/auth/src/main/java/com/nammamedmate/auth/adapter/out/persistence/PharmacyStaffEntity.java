package com.nammamedmate.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pharmacy_staff")
public class PharmacyStaffEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 100)
  private String name;

  @Column(name = "email", length = 255)
  private String email;

  @Column(name = "phone", length = 15)
  private String phone;

  @Column(name = "password_hash", nullable = false, length = 60)
  private String passwordHash;

  @Column(name = "pos_pin_hash", length = 60)
  private String posPinHash;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "failed_login_attempts", nullable = false)
  private short failedLoginAttempts;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "last_failed_at")
  private Instant lastFailedAt;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "invited_by")
  private UUID invitedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected PharmacyStaffEntity() {}

  public PharmacyStaffEntity(
      UUID id,
      String name,
      String email,
      String phone,
      String passwordHash,
      String posPinHash,
      String status,
      short failedLoginAttempts,
      Instant lockedUntil,
      Instant lastFailedAt,
      Instant lastLoginAt,
      UUID invitedBy,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.name = name;
    this.email = email;
    this.phone = phone;
    this.passwordHash = passwordHash;
    this.posPinHash = posPinHash;
    this.status = status;
    this.failedLoginAttempts = failedLoginAttempts;
    this.lockedUntil = lockedUntil;
    this.lastFailedAt = lastFailedAt;
    this.lastLoginAt = lastLoginAt;
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

  public String getPhone() {
    return phone;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getPosPinHash() {
    return posPinHash;
  }

  public String getStatus() {
    return status;
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
