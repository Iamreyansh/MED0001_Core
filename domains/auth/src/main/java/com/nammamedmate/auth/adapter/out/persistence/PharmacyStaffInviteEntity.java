package com.nammamedmate.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pharmacy_staff_invites")
public class PharmacyStaffInviteEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "staff_id", nullable = false)
  private UUID staffId;

  @Column(name = "pharmacy_id", nullable = false)
  private UUID pharmacyId;

  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected PharmacyStaffInviteEntity() {}

  public PharmacyStaffInviteEntity(
      UUID id,
      UUID staffId,
      UUID pharmacyId,
      String tokenHash,
      Instant expiresAt,
      Instant usedAt,
      Instant createdAt) {
    this.id = id;
    this.staffId = staffId;
    this.pharmacyId = pharmacyId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
    this.usedAt = usedAt;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getStaffId() {
    return staffId;
  }

  public UUID getPharmacyId() {
    return pharmacyId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getUsedAt() {
    return usedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
