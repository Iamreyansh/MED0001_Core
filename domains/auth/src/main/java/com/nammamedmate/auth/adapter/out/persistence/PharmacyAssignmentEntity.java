package com.nammamedmate.auth.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pharmacy_staff_assignment")
public class PharmacyAssignmentEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "staff_id", nullable = false)
  private UUID staffId;

  @Column(name = "pharmacy_id", nullable = false)
  private UUID pharmacyId;

  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  @Column(name = "is_active", nullable = false)
  private boolean isActive;

  @Column(name = "joined_at", nullable = false)
  private Instant joinedAt;

  @Column(name = "removed_at")
  private Instant removedAt;

  protected PharmacyAssignmentEntity() {}

  public PharmacyAssignmentEntity(
      UUID id,
      UUID staffId,
      UUID pharmacyId,
      UUID roleId,
      boolean isActive,
      Instant joinedAt,
      Instant removedAt) {
    this.id = id;
    this.staffId = staffId;
    this.pharmacyId = pharmacyId;
    this.roleId = roleId;
    this.isActive = isActive;
    this.joinedAt = joinedAt;
    this.removedAt = removedAt;
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

  public UUID getRoleId() {
    return roleId;
  }

  public boolean isActive() {
    return isActive;
  }

  public Instant getJoinedAt() {
    return joinedAt;
  }

  public Instant getRemovedAt() {
    return removedAt;
  }
}
